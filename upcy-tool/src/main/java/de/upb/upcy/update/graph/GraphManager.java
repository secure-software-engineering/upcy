package de.upb.upcy.update.graph;

import de.upb.maven.ecosystem.persistence.model.MvnArtifactNode;
import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.base.graph.GraphModel.Artifact;
import de.upb.upcy.base.graph.GraphModel.Dependency;
import de.upb.upcy.base.graph.GraphParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

public class GraphManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraphManager.class);

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
  private Graph<String, CustomEdge> shrinkedCG;

  public GraphManager(Path depGraphJsonFile) throws IOException {
    this.depGraphJsonFile = depGraphJsonFile;
    Pair<DefaultDirectedGraph<Artifact, Dependency>, GraphModel> defaultDirectedGraphGraphModelPair = GraphParser.parseGraph(
        depGraphJsonFile);
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

    //FIXME: this the actual --> unified dependency GRAPH
    CGBuilder cgBuilder = new CGBuilder(classPath, applicationClassDir, nodeMatchUtil);
    cgBuilder.computeCGs(applicationPkgs);
    shrinkedCG = cgBuilder.getShrinkedCG();


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

  public AsSubgraph<GraphModel.Artifact, GraphModel.Dependency> blossomGraphCompileOnly(){
   return new AsSubgraph<>(
        blossemedDepGraph,
        blossemedDepGraph.vertexSet().stream()
            .filter(GraphManager::isRelevantCompileDependency)
            .collect(Collectors.toSet()),
        blossemedDepGraph.edgeSet());
  }

  public Artifact getBlossomNodeFor(Artifact libToUpdateForMincut){
    return blossomGraphCreator.getBlossomNode(libToUpdateForMincut);
  }

  public Collection<GraphModel.Artifact> expandBlossomNodeFor(GraphModel.Artifact artifact ){
    return blossomGraphCreator.expandBlossomNode(artifact);
  }

  public Optional<Artifact> findInDefaultDirectedDependencyGraph(MvnArtifactNode sinkRootNode, boolean withVersion){
    return NodeMatchUtil.findInDepGraph(sinkRootNode, this.dependencyDefaultDirectedGraph, withVersion);
  }

  public void exportBlossomDepGraphToDot(){
    // export graph for debugging
    final DOTExporter<Artifact, Dependency> objectObjectDOTExporter =
        new DOTExporter<>();
    objectObjectDOTExporter.setVertexAttributeProvider(
        v -> {
          Map<String, Attribute> map = new LinkedHashMap<>();
          map.put("label", DefaultAttribute.createAttribute(v.toGav()));
          return map;
        });
    objectObjectDOTExporter.exportGraph(blossemedDepGraph, new File("out.dot"));

  }


}
