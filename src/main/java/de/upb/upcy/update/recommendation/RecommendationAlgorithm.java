package de.upb.upcy.update.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Stopwatch;
import de.upb.maven.ecosystem.persistence.dao.DaoMvnArtifactNode;
import de.upb.maven.ecosystem.persistence.dao.DoaMvnArtifactNodeImpl;
import de.upb.maven.ecosystem.persistence.dao.Neo4JConnector;
import de.upb.maven.ecosystem.persistence.model.DependencyRelation;
import de.upb.maven.ecosystem.persistence.model.MvnArtifactNode;
import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.base.graph.GraphParser;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.base.mvn.MavenSearchAPIClient;
import de.upb.upcy.update.recommendation.check.UpdateCheck;
import de.upb.upcy.update.recommendation.check.Violation;
import de.upb.upcy.update.recommendation.cypher.CypherQueryCreator;
import de.upb.upcy.update.recommendation.exception.CompatabilityComputeException;
import de.upb.upcy.update.recommendation.exception.EmptyCallGraphException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.EdmondsKarpMFImpl;
import org.jgrapht.alg.interfaces.MinimumSTCutAlgorithm;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.AsWeightedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compute update recommendations and their incompatibilities. 1. compute the naive update 2. find a
 * min-(s,t)-cut with fewer incompatibilities
 *
 * @author adann
 */
public class RecommendationAlgorithm {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationAlgorithm.class);
  private final DaoMvnArtifactNode doaMvnArtifactNode;

  private final MavenInvokerProject mavenInvokerProject;
  private NodeMatchUtil nodeMatchUtil;
  private Pair<DefaultDirectedGraph<GraphModel.Artifact, GraphModel.Dependency>, GraphModel>
      pairGraph;
  private Graph<String, CustomEdge> shrinkedCG;
  private boolean isInitialized;
  private DefaultDirectedGraph<GraphModel.Artifact, GraphModel.Dependency> depGraph;
  private GraphModel.Artifact rootNode;

  private Graph<GraphModel.Artifact, GraphModel.Dependency> blossemedDepGraph;
  private BlossomGraphCreator blossomGraphCreator;
  private CypherQueryCreator cypherQueryCreator;
  private String targetGav;

  public RecommendationAlgorithm(MavenInvokerProject mavenInvokerProject, Path depGraphJsonFile)
      throws IOException {
    LOGGER.debug("Init connection to Neo4j");
    Driver driver = Neo4JConnector.getDriver();
    LOGGER.info("Connected successfully to Neo4j");

    doaMvnArtifactNode = new DoaMvnArtifactNodeImpl(driver);
    this.mavenInvokerProject = mavenInvokerProject;
    this.pairGraph = getDepGraph(depGraphJsonFile);
    this.isInitialized = false;
  }

  // kick out non-compile dependencies and junit
  public static boolean isRelevantCompileDependency(GraphModel.Artifact artifact) {
    final boolean compile = artifact.getScopes().contains("compile");
    if (!compile) {
      return false;
    }
    return !StringUtils.contains(artifact.getArtifactId(), "junit");
  }

  public Pair<DefaultDirectedGraph<GraphModel.Artifact, GraphModel.Dependency>, GraphModel>
      getDepGraph(Path jsonDepGraph) throws IOException {
    pairGraph = GraphParser.parseGraph(jsonDepGraph);
    return pairGraph;
  }

  public void initProject() throws MavenInvokerProject.BuildToolException {
    if (this.isInitialized) {
      return;
    } else {
      this.mavenInvokerProject.initialize();
      Collection<String> moduleClassPath = this.mavenInvokerProject.getClassPath();
      depGraph = this.pairGraph.getLeft();
      rootNode =
          depGraph.vertexSet().stream()
              .filter(v -> depGraph.inDegreeOf(v) == 0)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("Could not find root node"));

      this.nodeMatchUtil = new NodeMatchUtil(rootNode);
      try {
        this.nodeMatchUtil.computeJarAndClassMapping(moduleClassPath);
      } catch (IOException e) {
        LOGGER.error("Failed computing class mapping", e);
      }

      final String rootNodeGav = this.nodeMatchUtil.toGav(rootNode);

      // get the applications / rootNodes packages
      final Set<String> classFQNs = nodeMatchUtil.getGavToClasses().get(rootNodeGav);
      Set<String> applicationPkgs = Collections.emptySet();
      if (classFQNs == null || classFQNs.isEmpty()) {
        LOGGER.error("Empty Class names for application");
      } else {
        applicationPkgs =
            classFQNs.stream()
                .map(
                    fqn -> {
                      final int index = fqn.lastIndexOf(".");
                      if (index > 0) {
                        return fqn.substring(0, index);
                      }
                      return fqn;
                    })
                .collect(Collectors.toSet());
      }
      // separate into classpath and application classes
      List<String> classPath = new ArrayList<>();
      List<String> applicationClassDir = new ArrayList<>();
      for (String cpEntry : moduleClassPath) {

        final Path path = Paths.get(cpEntry);
        if (Files.isDirectory(path)) {
          applicationClassDir.add(cpEntry);
        } else {
          classPath.add(cpEntry);
        }
      }

      // build blossom graph
      blossomGraphCreator = new BlossomGraphCreator(depGraph, rootNode);
      blossemedDepGraph = blossomGraphCreator.buildBlossomDepGraph();
      cypherQueryCreator = new CypherQueryCreator(blossomGraphCreator, nodeMatchUtil);

      // compute the input
      CGBuilder cgBuilder = new CGBuilder(classPath, applicationClassDir, nodeMatchUtil);
      cgBuilder.computeCGs(applicationPkgs);
      shrinkedCG = cgBuilder.getShrinkedCG();

      // set isInitialized
      this.isInitialized = true;
    }
  }

  public List<UpdateSuggestion> run(String gavOfLibraryToUpdate, String targetGav)
      throws MavenInvokerProject.BuildToolException {
    this.targetGav = targetGav;

    final String[] targetGavSplit = targetGav.split(":");
    if (targetGavSplit.length < 2) {
      LOGGER.error("TargetGAV does not contain a valid version information");
      return Collections.emptyList();
    }
    String lowerVersionBound = targetGavSplit[2];

    this.initProject();

    final GraphModel.Artifact libToUpdateInDepGraph =
        nodeMatchUtil
            .findInDepGraphByGav(gavOfLibraryToUpdate, depGraph, true)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Cannot find library to update with gav: " + gavOfLibraryToUpdate));

    if (shrinkedCG == null || shrinkedCG.vertexSet().isEmpty() || shrinkedCG.edgeSet().isEmpty()) {
      LOGGER.error("Empty shrinked CG");
    }

    List<UpdateSuggestion> updateSuggestions = new ArrayList<>();
    // run the algorithm

    // check which newer version are available on maven central
    List<String> newerVersions =
        getArtifactsWithNewerVersion(
            libToUpdateInDepGraph.getGroupId(),
            libToUpdateInDepGraph.getArtifactId(),
            lowerVersionBound);

    UpdateSuggestion simpleUpdateSuggestion =
        getSimpleUpdateSuggestion(libToUpdateInDepGraph, newerVersions);
    // get the weight -- if weight 0-- we are done
    if (simpleUpdateSuggestion.getStatus() == UpdateSuggestion.SuggestionStatus.SUCCESS
        && (simpleUpdateSuggestion.getViolations() == null
            || simpleUpdateSuggestion.getViolations().isEmpty())) {
      LOGGER.info("Simple Update does not produce any violations, Done");
      return Collections.singletonList(simpleUpdateSuggestion);
    }
    updateSuggestions.add(simpleUpdateSuggestion);

    // else we have violations continue with the min-cut approach
    // call min-cut and get update suggestions
    final List<UpdateSuggestion> minCutUpdateSuggestions =
        this.computeUpdateUsingMinCut(libToUpdateInDepGraph, newerVersions);
    updateSuggestions.addAll(minCutUpdateSuggestions);

    LOGGER.info("Done with min-cut");
    return updateSuggestions;
  }

  private UpdateSuggestion getSimpleUpdateSuggestion(
      GraphModel.Artifact libToUpdateInDepGraph, Collection<String> newerVersions) {

    // 1. check simple update
    // get the library to update and check which nodes are not updated
    // all nodes, which are in the dep-tree NOT after library (so, the ones in the depgraph before)
    Collection<GraphModel.Artifact> updatedNodes = new ArrayList<>();
    // only check on compile and included edges, since we want to find out which libraries are
    // included by the libToUpdate

    final AsSubgraph<GraphModel.Artifact, GraphModel.Dependency> depSubGraphOnlyCompileAndIncluded =
        new AsSubgraph<>(
            depGraph,
            depGraph.vertexSet().stream()
                .filter(RecommendationAlgorithm::isRelevantCompileDependency)
                .collect(Collectors.toSet()),
            depGraph.edgeSet().stream()
                .filter(x -> x.getResolution() == GraphModel.ResolutionType.INCLUDED)
                .collect(Collectors.toSet()));
    BreadthFirstIterator<GraphModel.Artifact, GraphModel.Dependency> breadthFirstIterator =
        new BreadthFirstIterator<>(depSubGraphOnlyCompileAndIncluded, libToUpdateInDepGraph);
    while (breadthFirstIterator.hasNext()) {
      final GraphModel.Artifact next = breadthFirstIterator.next();
      updatedNodes.add(next);
    }
    // the nodes that are not updated
    Collection<GraphModel.Artifact> unUpdatedNodes =
        depSubGraphOnlyCompileAndIncluded.vertexSet().stream()
            .filter(x -> !updatedNodes.contains(x))
            .collect(Collectors.toList());

    // get updateSubGraph - as received from neo4j
    DefaultDirectedGraph<MvnArtifactNode, DependencyRelation> updateSubGraph = null;
    String pickedVersion = "";
    for (String newVersion : newerVersions) {
      String neo4jQuery =
          cypherQueryCreator.createNeo4JQuery(
              depSubGraphOnlyCompileAndIncluded,
              Collections.singleton(libToUpdateInDepGraph),
              Collections.singleton(libToUpdateInDepGraph),
              libToUpdateInDepGraph,
              newVersion);
      LOGGER.trace(neo4jQuery);
      // query neo4j and get the update subgraph
      updateSubGraph = doaMvnArtifactNode.getGraph(neo4jQuery);

      if (updateSubGraph != null && !updateSubGraph.vertexSet().isEmpty()) {
        // we found a solution
        pickedVersion = newVersion;
        break;
      }
    }
    if (updateSubGraph == null || updateSubGraph.vertexSet().isEmpty()) {
      LOGGER.error("No solution found in Neo4j");
      UpdateSuggestion simpleUpdateSuggestion = new UpdateSuggestion();
      simpleUpdateSuggestion.setOrgGav(libToUpdateInDepGraph.toGav());
      simpleUpdateSuggestion.setTargetGav(targetGav);
      simpleUpdateSuggestion.setSimpleUpdate(true);
      simpleUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.NO_NEO4J_ENTRY);
      simpleUpdateSuggestion.setNrOfViolations(-1);
      simpleUpdateSuggestion.setNrOfViolatedCalls(-1);
      return simpleUpdateSuggestion;
    }

    UpdateCheck updateCheck =
        new UpdateCheck(
            shrinkedCG,
            depGraph,
            unUpdatedNodes,
            updateSubGraph,
            nodeMatchUtil,
            blossomGraphCreator,
            false);

    final Collection<Violation> simpleUpdateViolations;

    // store it as a suggestions
    UpdateSuggestion simpleUpdateSuggestion = new UpdateSuggestion();
    simpleUpdateSuggestion.setOrgGav(libToUpdateInDepGraph.toGav());

    String updateGav =
        libToUpdateInDepGraph.getGroupId()
            + ":"
            + libToUpdateInDepGraph.getArtifactId()
            + ":"
            + pickedVersion;
    simpleUpdateSuggestion.setTargetGav(targetGav);
    simpleUpdateSuggestion.setUpdateGav(updateGav);
    simpleUpdateSuggestion.setSimpleUpdate(true);
    // if target targetGav and updateGav differ we found a better solution than the naive update
    simpleUpdateSuggestion.setNaiveUpdate(StringUtils.equals(targetGav, updateGav));
    ArrayList<Pair<String, String>> updateSteps = new ArrayList<>();
    updateSteps.add(Pair.of(libToUpdateInDepGraph.toGav(), updateGav));
    simpleUpdateSuggestion.setUpdateSteps(updateSteps);
    try {
      simpleUpdateViolations =
          updateCheck.computeViolation(Collections.singletonList(libToUpdateInDepGraph));
      simpleUpdateSuggestion.setViolations(simpleUpdateViolations);
      simpleUpdateSuggestion.setNrOfViolations(
          Math.toIntExact(
              simpleUpdateViolations.stream()
                  .filter(x -> x.getViolatedCalls().size() > 0)
                  .count()));
      simpleUpdateSuggestion.setNrOfViolatedCalls(
          simpleUpdateViolations.stream()
              .mapToInt(x -> x == null ? 0 : x.getViolatedCalls().size())
              .sum());

      simpleUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.SUCCESS);
      LOGGER.info("Simple Update has {} violations", simpleUpdateViolations.size());
    } catch (CompatabilityComputeException e) {
      simpleUpdateSuggestion.setNrOfViolations(-1);
      simpleUpdateSuggestion.setNrOfViolatedCalls(-1);
      simpleUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.FAILED_SIGTEST);
    } catch (EmptyCallGraphException e) {
      simpleUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.EMPTY_CG);
      simpleUpdateSuggestion.setNrOfViolations(-1);
      simpleUpdateSuggestion.setNrOfViolatedCalls(-1);
    }

    return simpleUpdateSuggestion;
  }

  private List<UpdateSuggestion> computeUpdateUsingMinCut(
      GraphModel.Artifact libToUpdateInDepGraph, List<String> newerVersions) {

    LOGGER.info("Compute Min-Cut solution");
    List<UpdateSuggestion> updateSuggestions = new ArrayList<>();
    // export graph for debugging
    final DOTExporter<GraphModel.Artifact, GraphModel.Dependency> objectObjectDOTExporter =
        new DOTExporter<>();
    objectObjectDOTExporter.setVertexAttributeProvider(
        v -> {
          Map<String, Attribute> map = new LinkedHashMap<>();
          map.put("label", DefaultAttribute.createAttribute(v.toGav()));
          return map;
        });

    objectObjectDOTExporter.exportGraph(blossemedDepGraph, new File("out.dot"));

    final AsSubgraph<GraphModel.Artifact, GraphModel.Dependency> blossomGraphCompileOnly =
        new AsSubgraph<>(
            blossemedDepGraph,
            blossemedDepGraph.vertexSet().stream()
                .filter(RecommendationAlgorithm::isRelevantCompileDependency)
                .collect(Collectors.toSet()),
            blossemedDepGraph.edgeSet());

    // use the blossom-graph for the min-cut
    // init all edge weights
    Map<GraphModel.Dependency, Double> initWeights = new HashMap<>();
    blossomGraphCompileOnly.edgeSet().forEach(x -> initWeights.put(x, 1.0));

    final AsWeightedGraph<GraphModel.Artifact, GraphModel.Dependency> unDirectedDepGraph =
        new AsWeightedGraph<>(new AsUndirectedGraph<>(blossomGraphCompileOnly), initWeights);

    MinimumSTCutAlgorithm<GraphModel.Artifact, GraphModel.Dependency> minimumSTCutAlgorithm =
        new EdmondsKarpMFImpl<>(unDirectedDepGraph);

    Queue<GraphModel.Dependency> edgeWorklist = new ArrayDeque<>();

    // initialize the worklist, therefore randomly pick an edge and decrease it by 1
    // it is increased +1 in the loop
    final Object[] edgeArray = unDirectedDepGraph.edgeSet().toArray();
    if (edgeArray.length == 0) {
      LOGGER.error("No edges in dependency graph");
      throw new IllegalArgumentException("No edges in undirected dependency graph");
    }
    final GraphModel.Dependency randEdge = (GraphModel.Dependency) edgeArray[0];
    final double randEdgeWeight = unDirectedDepGraph.getEdgeWeight(randEdge);
    unDirectedDepGraph.setEdgeWeight(randEdge, randEdgeWeight - 1);
    edgeWorklist.add(randEdge);

    boolean zeroViolationFound = false;
    double minCutWeight = Double.MAX_VALUE;

    HashSet<MinCut> computedMinCuts = new HashSet<>();

    while (!zeroViolationFound && !edgeWorklist.isEmpty()) {
      final GraphModel.Dependency curEdge = edgeWorklist.poll();

      // inc the edge weight to compute all potential min-cuts
      final double edgeWeight = unDirectedDepGraph.getEdgeWeight(curEdge);
      unDirectedDepGraph.setEdgeWeight(curEdge, edgeWeight + 1);

      GraphModel.Artifact libToUpdateForMincut = libToUpdateInDepGraph;
      final GraphModel.Artifact blossomNode =
          blossomGraphCreator.getBlossomNode(libToUpdateForMincut);
      if (blossomNode != null) {
        libToUpdateForMincut = blossomNode;
      }

      final double cutWeight =
          minimumSTCutAlgorithm.calculateMinCut(rootNode, libToUpdateForMincut);
      if (cutWeight <= minCutWeight) {
        // should only be possible in the first round
        minCutWeight = cutWeight;
        LOGGER.info("found min-cut with weight: {}", minCutWeight);
      } else {
        // it is NOT another min-cut; since the weight is higher
        LOGGER.trace("more weight then min-cut");
        // Reduce weight again
        unDirectedDepGraph.setEdgeWeight(curEdge, 1);
        continue;
      }
      final Set<GraphModel.Dependency> cutEdges = minimumSTCutAlgorithm.getCutEdges();

      final Set<GraphModel.Artifact> sinkPartition = minimumSTCutAlgorithm.getSinkPartition();
      final Set<GraphModel.Artifact> sourcePartition = minimumSTCutAlgorithm.getSourcePartition();
      // store the min-cut, and check if we computed a duplicate
      MinCut minCut = new MinCut(cutWeight, cutEdges, sinkPartition, sourcePartition);
      if (!computedMinCuts.add(minCut)) {
        LOGGER.error("Already computed min-cut");
        continue;
      }

      // get the nodes in the sink -- that are the tgt nodes of the cutted edges
      List<GraphModel.Artifact> cuttedNodes = new ArrayList<>();
      for (GraphModel.Dependency cutEdge : cutEdges) {
        final GraphModel.Artifact edgeTarget = unDirectedDepGraph.getEdgeTarget(cutEdge);
        cuttedNodes.add(edgeTarget);
      }
      // also expand the blossom Nodes in the un-updated nodes -- akka the source partition
      {
        Set<GraphModel.Artifact> expandedNodes = new HashSet<>();
        for (Iterator<GraphModel.Artifact> iter = sourcePartition.iterator(); iter.hasNext(); ) {
          GraphModel.Artifact sourceNode = iter.next();
          final Collection<GraphModel.Artifact> artifacts =
              blossomGraphCreator.expandBlossomNode(sourceNode);
          if (artifacts != null && !artifacts.isEmpty()) {
            // we have a blossom node
            expandedNodes.addAll(artifacts);
            // remove it from the sink and add the org nodes
            iter.remove();
          }
        }
        // add the expanded nodes to the source partion
        sourcePartition.addAll(expandedNodes);
      }

      DefaultDirectedGraph<MvnArtifactNode, DependencyRelation> updateSubGraph = null;
      String pickedVersion = "";
      for (String newVersion : newerVersions) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        LOGGER.info("Neo4j Query Started");
        String neo4jQuery =
            cypherQueryCreator.createNeo4JQuery(
                depGraph,
                sinkPartition,
                new HashSet<>(cuttedNodes),
                libToUpdateInDepGraph,
                newVersion);
        LOGGER.trace(neo4jQuery);
        // query neo4j and get the update subgraph
        updateSubGraph = doaMvnArtifactNode.getGraph(neo4jQuery);
        stopwatch.stop();
        LOGGER.info("Query took: {}", stopwatch.elapsed(TimeUnit.MILLISECONDS));

        if (updateSubGraph != null && !updateSubGraph.vertexSet().isEmpty()) {
          // we found a solution
          pickedVersion = newVersion;
          break;
        }
      }
      // could not find solution
      if (updateSubGraph == null || updateSubGraph.vertexSet().isEmpty()) {
        LOGGER.error("No solution found in NEO4j");

        UpdateSuggestion failedUpdate = new UpdateSuggestion();
        failedUpdate.setNaiveUpdate(false);
        failedUpdate.setOrgGav(libToUpdateInDepGraph.toGav());
        failedUpdate.setTargetGav(targetGav);
        failedUpdate.setSimpleUpdate(false);
        failedUpdate.setCutWeight((int) Math.round(minCutWeight));
        failedUpdate.setStatus(UpdateSuggestion.SuggestionStatus.NO_NEO4J_ENTRY);
        failedUpdate.setNrOfViolations(-1);
        // DO not return but search in the next min-cut
        // return Collections.singletonList(failedUpdate);
        updateSuggestions.add(failedUpdate);

        // add the edges to the worklist and continue search
        // they are increased in the worklist step
        edgeWorklist.addAll(cutEdges);
        continue;
      }

      LOGGER.debug("Check Min-Cut Update");

      //  add update step info
      UpdateSuggestion minCutUpdateSuggestion = new UpdateSuggestion();
      minCutUpdateSuggestion.setOrgGav(libToUpdateInDepGraph.toGav());
      minCutUpdateSuggestion.setSimpleUpdate(false);
      minCutUpdateSuggestion.setNaiveUpdate(false);
      String updateGav =
          libToUpdateInDepGraph.getGroupId()
              + ":"
              + libToUpdateInDepGraph.getArtifactId()
              + ":"
              + pickedVersion;
      minCutUpdateSuggestion.setTargetGav(targetGav);
      minCutUpdateSuggestion.setUpdateGav(updateGav);
      minCutUpdateSuggestion.setCutWeight((int) Math.round(minCutWeight));

      UpdateCheck updateCheck =
          new UpdateCheck(
              shrinkedCG,
              depGraph,
              sourcePartition,
              updateSubGraph,
              nodeMatchUtil,
              blossomGraphCreator,
              true);
      Collection<Violation> updateViolations = null;
      try {
        //  -- the update nodes are the cut nodes
        Set<GraphModel.Artifact> expandedCuttedNodes = new HashSet<>();
        for (GraphModel.Artifact cutNode : cuttedNodes) {
          {
            final Collection<GraphModel.Artifact> artifacts =
                blossomGraphCreator.expandBlossomNode(cutNode);
            if (artifacts != null) {
              // is a blossom node
              expandedCuttedNodes.addAll(artifacts);
            } else {
              expandedCuttedNodes.add(cutNode);
            }
          }
        }
        updateViolations = updateCheck.computeViolation(expandedCuttedNodes);
        minCutUpdateSuggestion.setViolations(updateViolations);
        minCutUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.SUCCESS);
        minCutUpdateSuggestion.setNrOfViolations(
            Math.toIntExact(
                updateViolations.stream().filter(x -> x.getViolatedCalls().size() > 0).count()));
        minCutUpdateSuggestion.setNrOfViolatedCalls(
            updateViolations.stream()
                .mapToInt(x -> x == null ? 0 : x.getViolatedCalls().size())
                .sum());
        LOGGER.info("Found Min-Cut Update with violations: {}", updateViolations.size());
      } catch (CompatabilityComputeException e) {
        minCutUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.FAILED_SIGTEST);
        minCutUpdateSuggestion.setNrOfViolations(-1);
        minCutUpdateSuggestion.setNrOfViolatedCalls(-1);
      } catch (EmptyCallGraphException e) {
        minCutUpdateSuggestion.setStatus(UpdateSuggestion.SuggestionStatus.EMPTY_CG);
        minCutUpdateSuggestion.setNrOfViolations(-1);
        minCutUpdateSuggestion.setNrOfViolatedCalls(-1);
      }

      // avoid duplicate update steps
      Set<Pair<String, String>> updateSteps = new HashSet<>();

      // for each updated lib compute the update step
      // only the "root" nodes of the sink partition are actually updated- --> transformed to direct
      // dependencies
      {
        // FIX in version 1.6 -- output for blossom and cutted nodes
        DefaultDirectedGraph<MvnArtifactNode, DependencyRelation> finalUpdateSubGraph =
            updateSubGraph;
        final List<MvnArtifactNode> rootNodesOfSubGraph =
            updateSubGraph.vertexSet().stream()
                .filter(x -> finalUpdateSubGraph.inDegreeOf(x) == 0)
                .collect(Collectors.toList());
        // add the cutted nodes to the list
        Set<GraphModel.Artifact> expandedCuttedNodes = new HashSet<>();
        // expand the cutted nodes, so get all the blossoms
        for (GraphModel.Artifact artifact : cuttedNodes) {
          final Collection<GraphModel.Artifact> artifacts =
              blossomGraphCreator.expandBlossomNode(artifact);
          if (artifacts != null && !artifacts.isEmpty()) {
            expandedCuttedNodes.addAll(artifacts);
          } else {
            expandedCuttedNodes.add(artifact);
          }
        }

        for (MvnArtifactNode sinkRootNode : rootNodesOfSubGraph) {

          // the gav in the update subgraph
          final Optional<GraphModel.Artifact> first =
              nodeMatchUtil.findInDepGraph(sinkRootNode, depGraph, false);

          if (!first.isPresent()) {
            LOGGER.error(
                "Could not find update for node {} in updateSubGraph",
                sinkRootNode.getGroup() + ":" + sinkRootNode.getArtifact());
          } else {
            String tGav =
                first.get().getGroupId()
                    + ":"
                    + first.get().getArtifactId()
                    + ":"
                    + first.get().getVersion();
            updateSteps.add(
                Pair.of(
                    tGav,
                    sinkRootNode.getGroup()
                        + ":"
                        + sinkRootNode.getArtifact()
                        + ":"
                        + sinkRootNode.getVersion()));
          }
          // TODO add the blossom update steps
        }

        // find the corresponding nodes for the cutted nodes, and check those for updates, too
        for (GraphModel.Artifact artifact : expandedCuttedNodes) {
          Optional<MvnArtifactNode> first =
              nodeMatchUtil.findInNeo4jGraph(artifact, finalUpdateSubGraph, false);

          if (!first.isPresent()) {
            // for deps migrated to other group or artifact
            first = nodeMatchUtil.findLooseInNeo4jGraph(artifact, finalUpdateSubGraph, false);
          }
          if (first.isPresent()) {
            String tGav =
                artifact.getGroupId()
                    + ":"
                    + artifact.getArtifactId()
                    + ":"
                    + artifact.getVersion();
            updateSteps.add(
                Pair.of(
                    tGav,
                    first.get().getGroup()
                        + ":"
                        + first.get().getArtifact()
                        + ":"
                        + first.get().getVersion()));
          }
        }
      }

      minCutUpdateSuggestion.setUpdateSteps(new ArrayList<>(updateSteps));
      // add last to avoid changes in set ..
      updateSuggestions.add(minCutUpdateSuggestion);

      // check the weight of violations ...; if 0 (no violations) done; else compute further
      // min-cuts
      if (updateViolations == null || updateViolations.isEmpty()) {
        // we found a perfect solution stop
        zeroViolationFound = true;

      } else {
        // add the edges to the worklist and continue search
        edgeWorklist.addAll(cutEdges);
      }
    }
    return updateSuggestions;
  }

  private List<String> getArtifactsWithNewerVersion(
      String group, String artifact, String lowerBoundVersion) {
    DefaultArtifactVersion defaultArtifactVersion = new DefaultArtifactVersion(lowerBoundVersion);

    // number of artifacts that directly depends on this dependency
    // number of updates
    // check maven central for update version
    JsonNode listOfArtifacts = null;
    try {
      JsonNode response = MavenSearchAPIClient.getListOfArtifacts(group, artifact);
      listOfArtifacts = response.at("/response/docs");
    } catch (IOException e) {
      LOGGER.error("Failed to retrieve version from maven central", e);
    }
    if (listOfArtifacts == null || listOfArtifacts.isNull() || listOfArtifacts.isEmpty()) {
      LOGGER.error("Found no artifacts to update");
      return Collections.emptyList();
    }
    List<String> newerVersions = new ArrayList<>();
    // get the ones with a newer version
    for (Iterator<JsonNode> iterator = listOfArtifacts.iterator(); iterator.hasNext(); ) {
      final JsonNode next = iterator.next();
      final DefaultArtifactVersion nextVersion = new DefaultArtifactVersion(next.get("v").asText());

      if (nextVersion.compareTo(defaultArtifactVersion) >= 0) {
        newerVersions.add(next.get("v").asText());
      }
    }
    return newerVersions;
  }
}
