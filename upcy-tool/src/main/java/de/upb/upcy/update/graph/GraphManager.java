package de.upb.upcy.update.graph;

import de.upb.maven.ecosystem.persistence.model.MvnArtifactNode;
import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.base.graph.GraphModel.Artifact;
import de.upb.upcy.base.graph.GraphModel.Dependency;
import de.upb.upcy.base.graph.GraphParser;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.collections.MultiMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

public class GraphManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraphManager.class);
  private boolean initialized;
  private HashMap<String, Artifact> unifiedDepVertexToArtifact;

  // kick out non-compile dependencies and junit
  public static boolean isRelevantCompileDependency(Artifact artifact) {
    final boolean compile = artifact.getScopes().contains("compile");
    if (!compile) {
      return false;
    }
    return !StringUtils.contains(artifact.getArtifactId(), "junit");
  }

  public Path getDepGraphJsonFile() {
    return depGraphJsonFile;
  }

  private final Path depGraphJsonFile;

  private DefaultDirectedGraph<Artifact, Dependency> dependencyDefaultDirectedGraph;
  private GraphModel graphModel;

  private GraphModel.Artifact rootNode;
  private NodeMatchUtil nodeMatchUtil;

  public Graph<String, CustomEdge> getShrinkedCG() {
    return shrinkedCG;
  }

  public BlossomGraphCreator getBlossomGraphCreator() {
    return blossomGraphCreator;
  }

  public NodeMatchUtil getNodeMatchUtil() {
    return nodeMatchUtil;
  }

  public Graph<Artifact, Dependency> getBlossemedDepGraph() {
    return blossemedDepGraph;
  }

  private BlossomGraphCreator blossomGraphCreator;
  private Graph<Artifact, Dependency> blossemedDepGraph;
  private DefaultDirectedGraph<String, CustomEdge> shrinkedCG;
  private DefaultDirectedGraph<String, CustomEdge> unifiedDepGraph;

  public GraphManager(Path depGraphJsonFile) throws IOException {
    this.depGraphJsonFile = depGraphJsonFile;
    Pair<DefaultDirectedGraph<Artifact, Dependency>, GraphModel>
        defaultDirectedGraphGraphModelPair = GraphParser.parseGraph(depGraphJsonFile);
    graphModel = defaultDirectedGraphGraphModelPair.getValue();
    dependencyDefaultDirectedGraph = defaultDirectedGraphGraphModelPair.getKey();
  }

  public DefaultDirectedGraph<Artifact, Dependency> getDependencyDefaultDirectedGraph() {
    return dependencyDefaultDirectedGraph;
  }

  public GraphModel getGraphModel() {
    return graphModel;
  }

  public GraphModel.Artifact getRootNode() {
    if (this.rootNode == null) {
      rootNode =
          this.dependencyDefaultDirectedGraph.vertexSet().stream()
              .filter(v -> this.dependencyDefaultDirectedGraph.inDegreeOf(v) == 0)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("Could not find root node"));
    }
    return rootNode;
  }

  public void build(Collection<String> runtimeClassPath) {

    if (!this.initialized) {
      // compute the unified dependency and blossom graph

      this.nodeMatchUtil = new NodeMatchUtil(getRootNode());
      try {
        this.nodeMatchUtil.computeJarAndClassMapping(runtimeClassPath);
      } catch (IOException e) {
        LOGGER.error("Failed computing class mapping", e);
      }

      final String rootNodeGav = NodeMatchUtil.toGav(rootNode);

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
      for (String cpEntry : runtimeClassPath) {

        final Path path = Paths.get(cpEntry);
        if (Files.isDirectory(path)) {
          applicationClassDir.add(cpEntry);
        } else {
          classPath.add(cpEntry);
        }
      }

      // build blossom graph
      blossomGraphCreator = new BlossomGraphCreator(this.dependencyDefaultDirectedGraph, rootNode);
      blossemedDepGraph = blossomGraphCreator.buildBlossomDepGraph();

      // compute the input

      // FIXME: this the actual --> unified dependency GRAPH
      CGBuilder cgBuilder = new CGBuilder(classPath, applicationClassDir, nodeMatchUtil);
      cgBuilder.computeCGs(applicationPkgs);
      shrinkedCG = cgBuilder.getShrinkedCG();

      // build the unified dependency graph, based on the shrinked cg
      unifiedDepGraph = (DefaultDirectedGraph<String, CustomEdge>) shrinkedCG.clone();

      //  prune self-edges

      for (Iterator<CustomEdge> it = unifiedDepGraph.edgeSet().iterator(); it.hasNext(); ) {
        CustomEdge customEdge = it.next();
        String edgeSource = unifiedDepGraph.getEdgeSource(customEdge);
        String edgeTarget = unifiedDepGraph.getEdgeTarget(customEdge);
        if (edgeSource == edgeTarget || StringUtils.equals(edgeSource, edgeTarget)) {
          unifiedDepGraph.removeEdge(customEdge);
        }
      }

      unifiedDepVertexToArtifact = new HashMap<>();
      // compute mapping between ids in unifedDep Graph and the original dependency graph
      for (String vertex : unifiedDepGraph.vertexSet()) {
        Optional<Artifact> inDepGraphByGav = NodeMatchUtil.findInDepGraphByGav(vertex,
            this.dependencyDefaultDirectedGraph, true);
        unifiedDepVertexToArtifact.put(vertex, inDepGraphByGav.orElse(null));
      }

      //add "empty" edges from dependency graph
      for (Dependency dependencyEdge : dependencyDefaultDirectedGraph.edgeSet()) {
        Artifact edgeSource = dependencyDefaultDirectedGraph.getEdgeSource(dependencyEdge);
        Artifact edgeTarget = dependencyDefaultDirectedGraph.getEdgeTarget(dependencyEdge);

        Optional<String> inUnifiedDepGraphSource = NodeMatchUtil.findInUnifiedDepGraph(edgeSource,
            unifiedDepGraph, true);

        Optional<String> inUnifiedDepGraphTarget = NodeMatchUtil.findInUnifiedDepGraph(edgeTarget,
            unifiedDepGraph, true);

        if (inUnifiedDepGraphSource.isPresent() && inUnifiedDepGraphTarget.isPresent()) {
          // check if an edge already exists
          boolean containsEdge = unifiedDepGraph.containsEdge(inUnifiedDepGraphSource.get(),
              inUnifiedDepGraphTarget.get());
          if (!containsEdge) {
            // create empty edge in the graph to represent compile dependencies that have no edge in the callgraph --> that are not called
            unifiedDepGraph.addEdge(inUnifiedDepGraphSource.get(), inUnifiedDepGraphTarget.get(),
                new CustomEdge());
          }
        } else {
          LOGGER.error("Could not find dependency in unified dependency graph");
        }

      }

      // set initialized to true
      this.initialized = true;
    } else {
      return;
    }
  }

  public AsSubgraph<Artifact, Dependency> depSubGraphOnlyCompileAndIncluded() {
    return new AsSubgraph<>(
        this.dependencyDefaultDirectedGraph,
        this.dependencyDefaultDirectedGraph.vertexSet().stream()
            .filter(GraphManager::isRelevantCompileDependency)
            .collect(Collectors.toSet()),
        this.dependencyDefaultDirectedGraph.edgeSet().stream()
            .filter(x -> x.getResolution() == GraphModel.ResolutionType.INCLUDED)
            .collect(Collectors.toSet()));
  }

  public AsSubgraph<GraphModel.Artifact, GraphModel.Dependency> blossomGraphCompileOnly() {
    if (!this.initialized) {
      throw new IllegalStateException("The graph must be build first");
    }
    return new AsSubgraph<>(
        blossemedDepGraph,
        blossemedDepGraph.vertexSet().stream()
            .filter(GraphManager::isRelevantCompileDependency)
            .collect(Collectors.toSet()),
        blossemedDepGraph.edgeSet());
  }

  public Artifact getBlossomNodeFor(Artifact libToUpdateForMincut) {
    if (!this.initialized) {
      throw new IllegalStateException("The graph must be build first");
    }
    return blossomGraphCreator.getBlossomNode(libToUpdateForMincut);
  }

  public Collection<GraphModel.Artifact> expandBlossomNodeFor(GraphModel.Artifact artifact) {
    if (!this.initialized) {
      throw new IllegalStateException("The graph must be build first");
    }
    return blossomGraphCreator.expandBlossomNode(artifact);
  }

  public Optional<Artifact> findInDefaultDirectedDependencyGraph(
      MvnArtifactNode sinkRootNode, boolean withVersion) {
    return NodeMatchUtil.findInDepGraph(
        sinkRootNode, this.dependencyDefaultDirectedGraph, withVersion);
  }


  public void exportBlossomDepGraphToDot() {
    if (!this.initialized) {
      throw new IllegalStateException("The graph must be build first");
    }
    // export graph for debugging
    final DOTExporter<Artifact, Dependency> objectObjectDOTExporter = new DOTExporter<>();
    objectObjectDOTExporter.setVertexAttributeProvider(
        v -> {
          Map<String, Attribute> map = new LinkedHashMap<>();
          map.put("label", DefaultAttribute.createAttribute(v.toGav()));
          return map;
        });
    objectObjectDOTExporter.exportGraph(blossemedDepGraph, new File("out.dot"));
  }

  public void exportUnifiedDepGraphToJson(String outputFile) throws IOException {
    if (!this.initialized) {
      throw new IllegalStateException("The graph must be build first");
    }

    CustomUnifiedDepGraphJSONExporter customUnifiedDepGraphJsonExporter = new CustomUnifiedDepGraphJSONExporter(
        unifiedDepVertexToArtifact);
    customUnifiedDepGraphJsonExporter.exportGraph(
        this.unifiedDepGraph, new FileWriter(new File(outputFile)));
  }
}
