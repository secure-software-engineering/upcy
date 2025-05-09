package de.upb.upcy.update.recommendation.cypher;

import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.update.recommendation.BlossomGraphCreator;
import de.upb.upcy.update.recommendation.NodeMatchUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class to construct cypher queries for finding a solution to the min-(s,t)-cut */
public class CypherQueryCreator {

  private static final Logger LOGGER = LoggerFactory.getLogger(CypherQueryCreator.class);
  private final BlossomGraphCreator blossomGraphCreator;
  private final NodeMatchUtil nodeMatchUtil;

  public CypherQueryCreator(BlossomGraphCreator blossomGraphCreator, NodeMatchUtil nodeMatchUtil) {
    this.blossomGraphCreator = blossomGraphCreator;
    this.nodeMatchUtil = nodeMatchUtil;
  }

  /**
   * NOTE: Only checks for COMPILE dependency (see constraint of the relation)
   *
   * @param depGraphfinal the dependency graph
   * @param sinkPartition the sink partition
   * @param libToUpdateInDepGraph the library to update
   * @return the cypher query
   */
  public String createNeo4JQuery(
      final Graph<GraphModel.Artifact, GraphModel.Dependency> depGraphfinal,
      final Set<GraphModel.Artifact> sinkPartition,
      final Set<GraphModel.Artifact> cuttedNodes,
      final GraphModel.Artifact libToUpdateInDepGraph,
      final String targetVersion) {

    // use simple dijskstra for now
    ShortestPathAlgorithm<GraphModel.Artifact, GraphModel.Dependency> shortestPathAlgorithm =
        new DijkstraShortestPath<>(depGraphfinal);
    GraphModel.Artifact projectRootNode =
        depGraphfinal.vertexSet().stream()
            .filter(x -> depGraphfinal.inDegreeOf(x) == 0)
            .findFirst()
            .get();

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

    HashMap<GraphModel.Artifact, List<GraphModel.Artifact>> nodeToRoots = new HashMap<>();
    // query the root nodes
    //  the neo4j queries must be computed on the "real"-nodes not the blossem-graph, thus
    // we expand them in the sink partition
    Set<GraphModel.Artifact> expandedSikPartition = new HashSet<>();
    for (GraphModel.Artifact nodeInSink : sinkPartition) {
      {
        final Collection<GraphModel.Artifact> artifacts =
            blossomGraphCreator.expandBlossomNode(nodeInSink);
        if (artifacts != null) {
          // is a blossom node
          expandedSikPartition.addAll(artifacts);
        } else {
          expandedSikPartition.add(nodeInSink);
        }
      }
    }

    for (GraphModel.Artifact nodeInSink : expandedSikPartition) {

      Queue<GraphModel.Artifact> worklist = new ArrayDeque<>();
      worklist.add(nodeInSink);

      Set<GraphModel.Artifact> visitedNodes = new HashSet<>();
      while (!worklist.isEmpty()) {
        final GraphModel.Artifact polledNode = worklist.poll();
        if (visitedNodes.contains(polledNode)) {
          continue;
        }

        final Collection<GraphModel.Artifact> artifacts =
            blossomGraphCreator.expandBlossomNode(polledNode);
        // should be superfluous, but here for safety reasons
        if (artifacts != null) {
          // is a blossom node
          worklist.addAll(artifacts);
          continue;
        }

        List<GraphModel.Artifact> nextParents = Graphs.predecessorListOf(depGraphfinal, polledNode);
        // only continue with parents that are actually in the sink partition
        nextParents =
            nextParents.stream()
                .filter(expandedSikPartition::contains)
                .collect(Collectors.toList());

        if (nextParents.isEmpty()) {
          // add it to the hashmap
          final List<GraphModel.Artifact> rootLists =
              nodeToRoots.computeIfAbsent(nodeInSink, x -> new ArrayList<>());
          // !! IMPORTANT: -- WE ARE ONLY INTERESTED INTO THE NODES THAT ARE CUTTED AND THUS
          // ACTUALLY
          // UPDATED !!!
          if (expandedCuttedNodes.contains(polledNode)) {
            // check if it is one of the updated nodes
            rootLists.add(polledNode);
          }
        } else {
          worklist.addAll(nextParents);
        }
        visitedNodes.add(polledNode);
      }
    }
    // generate the neo4j query constraints
    // relationship name counter

    final List<GraphModel.Artifact> predDepNodeOfLibToUpdate =
        nodeToRoots.get(libToUpdateInDepGraph);
    MatchUpdateNodeQuery constAndSubGraph =
        generateLibToUpdateConstraints(libToUpdateInDepGraph, targetVersion);

    List<SinkRootQuery> queries =
        createSinkRootNodeConstraints(
            libToUpdateInDepGraph,
            nodeToRoots,
            targetVersion,
            shortestPathAlgorithm,
            projectRootNode);

    HashSet<GraphModel.Artifact> boundNodes = new HashSet<>();
    String matchQuery = constAndSubGraph.generateQuery(boundNodes);
    boundNodes.addAll(constAndSubGraph.getBoundNodes());

    // subgraph matching should be at the end to improve performance, first select and then subgraph
    // match
    // process statements one
    // thus, we sort here by sharedNode --> if sharedNode is libToUpdate (subgraph matching)
    final List<SinkRootQuery> sortedQueries =
        queries.stream()
            .sorted(
                (x1, x2) -> {
                  // if x1 < x2 = -1; x1 ==x2 = 0, else 1
                  if (x1.getSharedNode() == libToUpdateInDepGraph
                      && x2.getSharedNode() == libToUpdateInDepGraph) {
                    return 0;
                  } else if (x1.getSharedNode() == libToUpdateInDepGraph) {
                    return 1;
                  } else if (x2.getSharedNode() == libToUpdateInDepGraph) {
                    return -1;
                  } else {
                    // we don't care
                    return 0;
                  }
                })
            .collect(Collectors.toList());

    List<String> queriesStr = new ArrayList<>();
    List<String> subGraphQuery = new ArrayList<>();

    for (SinkRootQuery query : sortedQueries) {
      queriesStr.add(query.generateQuery(boundNodes));
      boundNodes.addAll(query.getBoundNodes());
      // subgraph queries

      subGraphQuery.add(query.getSubGraph(boundNodes));
    }

    return matchQuery
        + "\n\n"
        + String.join("\n", queriesStr)
        + "\n\n"
        + String.join("\n", subGraphQuery)
        + "\n"
        + "RETURN *";
  }

  private List<SinkRootQuery> createSinkRootNodeConstraints(
      GraphModel.Artifact libToUpdateInDepGraph,
      HashMap<GraphModel.Artifact, List<GraphModel.Artifact>> nodeToRoots,
      String targetVersion,
      ShortestPathAlgorithm<GraphModel.Artifact, GraphModel.Dependency> shortestPathAlgorithm,
      GraphModel.Artifact projectRootNode) {

    List<SinkRootQuery> queries = new ArrayList<>();
    // 2. then generate the constraints only for nodes with >=2 roots,
    final List<Map.Entry<GraphModel.Artifact, List<GraphModel.Artifact>>> collect =
        new ArrayList<>(nodeToRoots.entrySet());

    for (Map.Entry<GraphModel.Artifact, List<GraphModel.Artifact>> entry : collect) {
      GraphModel.Artifact sharedNode = entry.getKey();

      final Map<GraphModel.Artifact, List<GraphModel.Artifact>> sinkRoots =
          entry.getValue().stream()
              .collect(
                  Collectors.groupingBy(
                      x -> {
                        final GraphModel.Artifact blossomNode =
                            blossomGraphCreator.getBlossomNode(x);
                        if (blossomNode != null) {
                          return blossomNode;
                        } else {
                          return x;
                        }
                      }));

      final SinkRootQuery sinkRootQuery =
          new SinkRootQuery(
              sinkRoots,
              sharedNode,
              libToUpdateInDepGraph,
              blossomGraphCreator,
              targetVersion,
              shortestPathAlgorithm);
      queries.add(sinkRootQuery);
    }
    // filter queries that have the same targetBlossom and the same sourceBlossom
    final Map<GraphModel.Artifact, List<SinkRootQuery>> targetBlossoms =
        queries.stream()
            .collect(
                Collectors.groupingBy(
                    x -> {
                      final GraphModel.Artifact blossomNode =
                          blossomGraphCreator.getBlossomNode(x.getSharedNode());
                      if (blossomNode != null) {
                        return blossomNode;
                      } else {
                        return x.getSharedNode();
                      }
                    }));
    // for each targetBlossom check if there exists multiple sinkrootquieres with the same source(s)
    for (Map.Entry<GraphModel.Artifact, List<SinkRootQuery>> entry : targetBlossoms.entrySet()) {
      // sort the sinkRoots by distance to the project root
      final List<SinkRootQuery> sortedQueriesBySharedNode =
          entry.getValue().stream()
              .sorted(
                  Comparator.comparingDouble(
                      x -> shortestPathAlgorithm.getPathWeight(projectRootNode, x.getSharedNode())))
              .collect(Collectors.toList());
      HashSet<GraphModel.Artifact> doneSourceBlossoms = new HashSet<>();
      // do not create a constraint if it already has been done

      for (SinkRootQuery rootQuery : sortedQueriesBySharedNode) {
        // don't touch the libToUpdate Query
        if (rootQuery.getSharedNode() == libToUpdateInDepGraph) {
          //  not so nice but works
          continue;
        }

        Iterator<GraphModel.Artifact> iter = rootQuery.getSinkRoots().keySet().iterator();
        while (iter.hasNext()) {

          final GraphModel.Artifact nextArtifact = iter.next();
          GraphModel.Artifact blossomNode = blossomGraphCreator.getBlossomNode(nextArtifact);
          if (blossomNode == null) {
            blossomNode = nextArtifact;
          }
          if (!doneSourceBlossoms.add(blossomNode)) {
            // was already in the set, thus we don't need a further constraint
            iter.remove();
          }
        }
      }
    }

    return queries;
  }

  private MatchUpdateNodeQuery generateLibToUpdateConstraints(
      GraphModel.Artifact libToUpdateInDepGraph, String targetVersion) {

    return new MatchUpdateNodeQuery(libToUpdateInDepGraph, targetVersion);
  }
}
