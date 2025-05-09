package de.upb.upcy.update.recommendation.check;

import com.google.common.collect.Lists;
import de.upb.maven.ecosystem.persistence.model.DependencyRelation;
import de.upb.maven.ecosystem.persistence.model.MvnArtifactNode;
import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.update.recommendation.BlossomGraphCreator;
import de.upb.upcy.update.recommendation.CustomEdge;
import de.upb.upcy.update.recommendation.NodeMatchUtil;
import de.upb.upcy.update.recommendation.compatabilityparser.CompatabilityCheck;
import de.upb.upcy.update.recommendation.compatabilityparser.Incompatibility;
import de.upb.upcy.update.recommendation.compatabilityparser.Parser;
import de.upb.upcy.update.recommendation.compatabilityparser.SigTestIncompatibility;
import de.upb.upcy.update.recommendation.compatabilityparser.SootMethodIncompatibility;
import de.upb.upcy.update.recommendation.exception.CompatabilityComputeException;
import de.upb.upcy.update.recommendation.exception.EmptyCallGraphException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootMethod;

/**
 * Compute violations for a given update using the CompatibilityCheck
 *
 * @author adann
 */
public class UpdateCheck {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateCheck.class);

  private final CompatabilityCheck COMPATABILITY_CHECK = CompatabilityCheck.getInstance();

  private final Graph<String, CustomEdge> shrinkedCG;
  private final Graph<GraphModel.Artifact, GraphModel.Dependency> dependencyGraph;

  private final Graph<MvnArtifactNode, DependencyRelation> updateSubGraph;
  private final NodeMatchUtil nodeMatchUtil;
  private final GraphModel.Artifact projectRoot;
  private final ShortestPathAlgorithm<GraphModel.Artifact, GraphModel.Dependency>
      shortestPathDepTree;

  private final Collection<GraphModel.Artifact> unUpdatedNodes;
  private final BlossomGraphCreator blossomGraphCreator;
  private final boolean treatBlossomNodesAsCompatible;

  public UpdateCheck(
      Graph<String, CustomEdge> shrinkedCG,
      Graph<GraphModel.Artifact, GraphModel.Dependency> dependencyGraph,
      Collection<GraphModel.Artifact> unUpdatedNodes,
      Graph<MvnArtifactNode, DependencyRelation> updateSubGraph,
      NodeMatchUtil nodeMatchUtil,
      BlossomGraphCreator blossomGraphCreator,
      boolean treatBlossomNodesAsCompatible) {
    this.shrinkedCG = shrinkedCG;
    this.dependencyGraph = dependencyGraph;
    this.updateSubGraph = updateSubGraph;
    this.nodeMatchUtil = nodeMatchUtil;
    this.projectRoot =
        dependencyGraph.vertexSet().stream()
            .filter(x -> dependencyGraph.inDegreeOf(x) == 0)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Could not find project root"));
    this.shortestPathDepTree = new BFSShortestPath<>(dependencyGraph);
    this.unUpdatedNodes = unUpdatedNodes;
    this.blossomGraphCreator = blossomGraphCreator;
    // if the blossom nodes are updated together, they are compatible
    this.treatBlossomNodesAsCompatible = treatBlossomNodesAsCompatible;
  }

  public static SigTestMethod parseSigTestMethodSignature(final String qualifiedMethod) {
    String currMethodString = qualifiedMethod;
    int idx = currMethodString.indexOf("(");
    String parameterString;
    List<String> parameters = Collections.emptyList();
    if (idx > -1) {
      parameterString = currMethodString.substring(idx + 1, currMethodString.length() - 1);
      parameters = Arrays.asList(parameterString.split(","));
      currMethodString = currMethodString.substring(0, idx);
    }
    // get the method name
    String nameString;
    String returnVal;
    List<String> qualifiers = new ArrayList<>();
    final String[] splitMethod = currMethodString.split(" ");
    // the last element is the name
    nameString = splitMethod[splitMethod.length - 1];
    // the element before the return type
    returnVal = splitMethod[splitMethod.length - 2];
    // everything else, are qualifiers
    for (int i = splitMethod.length - 3; i >= 0; i--) {
      qualifiers.add(splitMethod[i]);
    }
    qualifiers = Lists.reverse(qualifiers);

    idx = nameString.lastIndexOf(".");
    if (idx > -1) {
      nameString = nameString.substring(idx + 1);
    }

    final SigTestMethod sigTestMethod = new SigTestMethod();
    sigTestMethod.name = nameString;
    sigTestMethod.parameters = parameters;
    sigTestMethod.returnType = returnVal;
    sigTestMethod.qualifier = qualifiers;
    return sigTestMethod;
  }

  public static List<SootMethod> getSourceBinEdgeViolation(
      Collection<? extends Incompatibility> incompatibilities, CustomEdge customEdge)
      throws CompatabilityComputeException {
    if (incompatibilities == null) {
      LOGGER.error("Incompatibilities is NULL");
      throw new CompatabilityComputeException("Incompatibilities is NULL");
    }

    List<SootMethod> violatedCalls = new ArrayList<>();
    for (Pair<SootMethod, SootMethod> srcTgtMethod : customEdge.getSrcTgtMethods()) {
      // keep in mind, that also a class maybe delete
      final SootMethod tgtMethod = srcTgtMethod.getRight();
      final String tgtClass = tgtMethod.getDeclaringClass().getName();

      final List<SigTestIncompatibility> incComClasses =
          incompatibilities.stream()
              .filter(x -> x instanceof SigTestIncompatibility)
              .map(x -> (SigTestIncompatibility) x)
              .filter(x -> StringUtils.equals(x.getClassName(), tgtClass))
              .collect(Collectors.toList());

      if (incComClasses.isEmpty()) {
        // no incompatibility found for the tgt class
        continue;
      }
      for (SigTestIncompatibility incompatibility : incComClasses) {
        // check if the class has been deleted
        if ((incompatibility.getFieldNames() == null || incompatibility.getFieldNames().isEmpty())
            && (incompatibility.getInterfaceNames() == null
                || incompatibility.getInterfaceNames().isEmpty())
            && (incompatibility.getMethodNames() == null
                || incompatibility.getMethodNames().isEmpty())) {
          // the class has been deleted
          violatedCalls.add(tgtMethod);
          continue;
        }
        // if the class has not been deleted, check if the method has been deleted

        if (incompatibility.getMethodNames() == null) {
          continue;
        }

        // parse from right-to-left
        for (String methodName : incompatibility.getMethodNames()) {
          final SigTestMethod sigTestMethod = parseSigTestMethodSignature(methodName);
          // check if it matches the soot method
          // check if method name matches
          if (StringUtils.equals(tgtMethod.getName(), sigTestMethod.name)) {
            // check if return type matches
            if (StringUtils.equals(
                tgtMethod.getReturnType().toQuotedString(), sigTestMethod.returnType)) {
              // check if parameters matches
              if (tgtMethod.getParameterCount() == sigTestMethod.parameters.size()) {
                boolean parameterMatch = true;
                for (int i = 0; i < tgtMethod.getParameterCount(); i++) {
                  parameterMatch &=
                      StringUtils.equals(
                          tgtMethod.getParameterType(i).toQuotedString(),
                          sigTestMethod.parameters.get(i));
                }
                if (parameterMatch) {
                  violatedCalls.add(tgtMethod);

                  LOGGER.debug(
                      "Matching SootMethod {} with SigTestMethod {}",
                      tgtMethod.getSignature(),
                      methodName);
                  break;
                }
              }
            }
          }
        }
      }
    }
    return violatedCalls;
  }

  /**
   * @param initUpdatedNodes - the "initially" updated nodes, for the simple case just the updated
   *     dep; for the min-(s,t)-cut all updated "root-nodes" in T
   * @return the violations
   */
  public Collection<Violation> computeViolation(Collection<GraphModel.Artifact> initUpdatedNodes)
      throws CompatabilityComputeException, EmptyCallGraphException {
    List<Violation> foundViolations = new ArrayList<>();
    for (MvnArtifactNode nodeInUpdateSubGraph : updateSubGraph.vertexSet()) {
      // note that: if a dependency vanishes (fällt weg) becomes no longer necessary in the
      // updatedSubgraph,
      // then mvn includes the correct version

      // case 1. check if it has a match in the dep graph
      final Optional<GraphModel.Artifact> inDepGraph =
          nodeMatchUtil.findInDepGraph(nodeInUpdateSubGraph, dependencyGraph, false);
      if (inDepGraph.isPresent()) {
        GraphModel.Artifact orgDepNode = inDepGraph.get();
        // we have to check if we overwrite (or replace it in the dep graph)
        // case 1.1
        if (StringUtils.equals(orgDepNode.getVersion(), nodeInUpdateSubGraph.getVersion())) {
          // we do not change the dep tree--- we are happy
          LOGGER.info(
              "The version of the dependency is unchanged: {}",
              nodeInUpdateSubGraph.getGroup()
                  + ":"
                  + nodeInUpdateSubGraph.getArtifact()
                  + ":"
                  + nodeInUpdateSubGraph.getVersion());
          continue;
        } else {
          // case 1.2 the version of the new dep differs from the org one
          final Collection<Violation> violations =
              checkUpdateOfDep(orgDepNode, nodeInUpdateSubGraph, initUpdatedNodes);
          foundViolations.addAll(violations);
        }

      } else {
        // case 2 - nodeInUpdateSubGraph has no match in the old graph, and thus is completely new,
        // thus everyone is happy
        LOGGER.info(
            "New dependency found: {}",
            nodeInUpdateSubGraph.getGroup()
                + ":"
                + nodeInUpdateSubGraph.getArtifact()
                + ":"
                + nodeInUpdateSubGraph.getVersion());
        continue;
      }
    }
    return foundViolations;
  }

  /**
   * @param orgDepNode - the dependency that is updated in the DepGraph (can be any of the updated
   *     ones)
   * @param newDepNode - the newDepNode -- that is the new version of the orgDepNode
   * @param initUpdatedDepNodes - the nodes that were "initially" Updated, e.g., the root nodes of
   *     the partitioned Graph in the Min-cut algorithm
   */
  private Collection<Violation> checkUpdateOfDep(
      GraphModel.Artifact orgDepNode,
      MvnArtifactNode newDepNode,
      Collection<GraphModel.Artifact> initUpdatedDepNodes)
      throws CompatabilityComputeException, EmptyCallGraphException {

    // double check, that the input is correct
    if (newDepNode == null) {
      // the Dep is removed in the new version, thus no violation will occur
      LOGGER.info("New dependency is null");
      return Collections.emptyList();
    }

    if (StringUtils.equals(orgDepNode.getVersion(), newDepNode.getVersion())) {
      // we do not change the dep tree --- we are happy
      LOGGER.info(
          "The version of the dependency is unchanged: {}",
          newDepNode.getGroup() + ":" + newDepNode.getArtifact() + ":" + newDepNode.getVersion());
      return Collections.emptyList();
    }

    // check if we actually overwrite the orgDepNode, by checking if it is the shortest path
    final GraphPath<GraphModel.Artifact, GraphModel.Dependency> pathInDepGraph =
        shortestPathDepTree.getPath(this.projectRoot, orgDepNode);
    if (pathInDepGraph == null) {
      // dep to check is not a dependency of libToUpdate
      throw new IllegalArgumentException("Could not find org dependency in DepGraph");
    }

    boolean isShortestPathOverInitUpdatedDepNode = false;
    for (GraphModel.Artifact node : initUpdatedDepNodes) {
      final boolean contains = pathInDepGraph.getVertexList().contains(node);
      if (contains) {
        isShortestPathOverInitUpdatedDepNode = true;
        break;
      }
    }
    // case 1.2.1 --> s->orgDepNode (over initUpdateNode) is the shortest path
    if (isShortestPathOverInitUpdatedDepNode) {
      final Map<String, CustomEdge> sourceNodeViolatedEdge = computeViolatedEdges(orgDepNode);
      return compatibilityCheck(orgDepNode, newDepNode, sourceNodeViolatedEdge);
    } else {
      // case 1.2.2 s->orgDepNode (over initUpdateNode) is NOT the shortest path
      final ShortestPathAlgorithm<MvnArtifactNode, DependencyRelation> updateSubGraphShortestPath =
          new BFSShortestPath<>(updateSubGraph);

      GraphPath<MvnArtifactNode, DependencyRelation> pathAfterTransformation = null;
      for (GraphModel.Artifact node : initUpdatedDepNodes) {
        final Optional<MvnArtifactNode> inNeo4jGraph =
            nodeMatchUtil.findInNeo4jGraph(node, updateSubGraph, false);
        if (!inNeo4jGraph.isPresent()) {
          LOGGER.error("Could not find the updated Node in the Dep");
          continue;
        }
        // path length after transformation, over the initial updated node "node"
        final GraphPath<MvnArtifactNode, DependencyRelation> nextPath =
            updateSubGraphShortestPath.getPath(inNeo4jGraph.get(), newDepNode);
        if (nextPath == null) {
          continue;
        } else if (pathAfterTransformation != null
            && nextPath.getLength() < pathAfterTransformation.getLength()) {
          pathAfterTransformation = nextPath;
        } else if (pathAfterTransformation == null) {
          pathAfterTransformation = nextPath;
        }
      }
      if (pathAfterTransformation == null) {
        // we did not find a path to the updated dep
        LOGGER.error("we did not find a path to the updated dep");
        return Collections.emptyList();
      }

      // case a) length(s->v) >= (s->v'), we overwrite the orgDepNode
      if (pathInDepGraph.getLength() >= (pathAfterTransformation.getLength() + 1)) {
        // +1 for the start node s, after the transformation
        //  same check as case 1.2.1
        final Map<String, CustomEdge> sourceNodeViolatedEdge = computeViolatedEdges(orgDepNode);
        return compatibilityCheck(orgDepNode, newDepNode, sourceNodeViolatedEdge);
      } else {
        // case b) length(s->v) < (s->v'), we keep the old dependency orgDepNode, and thus it must
        // be FORWARD_COMPATIBLE BUT we don't know the calls...
        // will only work if 100% forward compatible
        // Future -- refine forward compatability check
        return Collections.singletonList(
            new ForwardViolation(
                Collections.emptyList(),
                orgDepNode.toGav(),
                newDepNode.getGroup()
                    + ":"
                    + newDepNode.getArtifact()
                    + ":"
                    + newDepNode.getVersion()));
      }
    }
  }

  // example source: io.netty.channel.AbstractChannel$AbstractUnsafe:            method public final
  // void io.netty.channel.AbstractChannel$AbstractUnsafe.close(io.netty.channel.ChannelPromise)

  // example bin:
  // method public final okhttp3.internal.ws.MessageInflater
  // okhttp3.internal.ws.WebSocketExtensions.newMessageInflater(boolean)

  //  if orgDepNode in blossom --- !! NO violated EDGE since it is updated!!!
  private Map<String, CustomEdge> computeViolatedEdges(GraphModel.Artifact orgDepNode)
      throws EmptyCallGraphException {
    Map<String, CustomEdge> sourceAndViolatedEdge = new HashMap<>();

    if (shrinkedCG == null) {
      LOGGER.error("No CallGraph");
      throw new EmptyCallGraphException("ShrinkedCG is null");
    }

    // 1.1 compute the cg nodes representing the orgDepNode
    String cgNodeOfOrgDepNode = null;
    {
      final Optional<String> inCG = nodeMatchUtil.findInDepGraphByGav(orgDepNode, shrinkedCG, true);
      if (inCG.isPresent()) {
        cgNodeOfOrgDepNode = inCG.get();
      } else {
        LOGGER.warn("Could not find orgDepNode in CallGraph");
      }
    }
    ArrayList<String> cgNodesNotUpdated = new ArrayList<>();
    for (GraphModel.Artifact dep : unUpdatedNodes) {
      final Optional<String> inCG = nodeMatchUtil.findInDepGraphByGav(dep, shrinkedCG, true);
      // if we assume that all blossoms are updated
      if (treatBlossomNodesAsCompatible) {
        final boolean blossomNode = blossomGraphCreator.isBlossomNode(orgDepNode, dep);
        if (blossomNode) {
          // they are updated together, thus they are compatible
          continue;
        }
      }
      inCG.ifPresent(cgNodesNotUpdated::add);
    }

    if (cgNodeOfOrgDepNode == null) {
      LOGGER.error("Could not find node in cg: {}", orgDepNode);
      return Collections.emptyMap();
    }

    // search for edges from the  unUpdatedNodes to the updatedNode

    final Set<CustomEdge> customEdges = shrinkedCG.incomingEdgesOf(cgNodeOfOrgDepNode);
    // is the edge starting in a NotUpdated Lib?
    for (CustomEdge customEdge : customEdges) {
      final String edgeSource = shrinkedCG.getEdgeSource(customEdge);
      final boolean contains = cgNodesNotUpdated.contains(edgeSource);
      if (contains) {
        sourceAndViolatedEdge.put(edgeSource, customEdge);
      }
    }
    return sourceAndViolatedEdge;
  }

  private Collection<Violation> compatibilityCheck(
      GraphModel.Artifact orgDepNode,
      MvnArtifactNode newDepNode,
      Map<String, CustomEdge> sourceNodeViolatedEdge)
      throws CompatabilityComputeException {
    // if no violated Edges, then no violations
    if (sourceNodeViolatedEdge == null || sourceNodeViolatedEdge.isEmpty()) {
      return Collections.emptyList();
    }

    List<SootMethod> violatedCalls = new ArrayList<>();

    // get compatability info from the database
    // get the infos from sigtest and sootdiff
    Map<Parser.COMPATABILITY_TYPE, Collection<? extends Incompatibility>> compatabilityInfo =
        COMPATABILITY_CHECK.getCompatabilityInfo(
            orgDepNode.getGroupId(),
            orgDepNode.getArtifactId(),
            orgDepNode.getVersion(),
            newDepNode.getGroup(),
            newDepNode.getArtifact(),
            newDepNode.getVersion());

    // check for violations using the CG
    for (Map.Entry<String, CustomEdge> violatedEdgeSource : sourceNodeViolatedEdge.entrySet()) {

      if (violatedEdgeSource == null) {
        LOGGER.info("No violating edges given");
        continue;
      }

      final Optional<String> inCGRootNode =
          nodeMatchUtil.findInDepGraphByGav(this.projectRoot, this.shrinkedCG, true);
      if (!inCGRootNode.isPresent()) {
        LOGGER.error("Could not find node in CG");
        continue;
      }
      boolean checkSrc = violatedEdgeSource.getKey().equals(inCGRootNode.get());
      boolean checkBin = !checkSrc;

      try {
        LOGGER.debug("Check Semantic Edges");
        final List<SootMethod> semEdgeViolation =
            getSemanticEdgeViolation(
                compatabilityInfo.get(Parser.COMPATABILITY_TYPE.SEMANTIC),
                violatedEdgeSource.getValue());

        violatedCalls.addAll(semEdgeViolation);

      } catch (CompatabilityComputeException ex) {
        // if this fails, still continue
        LOGGER.debug("Could not check semantic");
      }

      // is src or binary
      if (checkSrc) {
        LOGGER.debug("Check Src Edges");
        final List<SootMethod> sourceBinEdgeViolation =
            getSourceBinEdgeViolation(
                compatabilityInfo.get(Parser.COMPATABILITY_TYPE.SRC),
                violatedEdgeSource.getValue());

        violatedCalls.addAll(sourceBinEdgeViolation);
      }

      if (checkBin) {
        LOGGER.debug("Check Bin Edges");
        final List<SootMethod> sourceBinEdgeViolation =
            getSourceBinEdgeViolation(
                compatabilityInfo.get(Parser.COMPATABILITY_TYPE.BINARY),
                violatedEdgeSource.getValue());

        violatedCalls.addAll(sourceBinEdgeViolation);
      }
    }

    // create violation
    Violation violation =
        new Violation(
            violatedCalls,
            orgDepNode.toGav(),
            newDepNode.getGroup() + ":" + newDepNode.getArtifact() + ":" + newDepNode.getVersion());

    return Collections.singletonList(violation);
  }

  private List<SootMethod> getSemanticEdgeViolation(
      Collection<? extends Incompatibility> incompatabilities, CustomEdge customEdge)
      throws CompatabilityComputeException {
    if (incompatabilities == null) {
      LOGGER.error("Semantic Incompatibilities is NULL");
      throw new CompatabilityComputeException("Semantic Incompatibilities is NULL");
    }

    Collection<SootMethodIncompatibility> sootMethodIncompatibilities =
        incompatabilities.stream()
            .filter(x -> x instanceof SootMethodIncompatibility)
            .map(x -> (SootMethodIncompatibility) x)
            .collect(Collectors.toList());

    List<SootMethod> violatedCalls = new ArrayList<>();
    for (Pair<SootMethod, SootMethod> srcTgtMethod : customEdge.getSrcTgtMethods()) {
      final SootMethod tgtMeth = srcTgtMethod.getRight();
      for (SootMethodIncompatibility sootMethodIncompatability : sootMethodIncompatibilities) {
        if (StringUtils.equals(
            tgtMeth.getSignature(), sootMethodIncompatability.getStartMethod())) {
          violatedCalls.add(tgtMeth);
        }
      }
    }
    return violatedCalls;
  }

  public static class SigTestMethod {
    String name;
    List<String> qualifier;
    String returnType;
    List<String> parameters;
  }
}
