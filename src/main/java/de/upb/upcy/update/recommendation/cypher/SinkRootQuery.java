package de.upb.upcy.update.recommendation.cypher;

import static java.util.stream.Collectors.groupingBy;

import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.update.recommendation.BlossomGraphCreator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;

public class SinkRootQuery implements CypherQuery {

  private static final int SUBGRAPH_LIMIT = 10;
  private static int MIN_PATH_LENGTH = 5;

  static {
    String pathLength = System.getenv("MIN_PATH_LENGTH");
    if (StringUtils.isNotBlank(pathLength)) {
      try {
        MIN_PATH_LENGTH = Integer.parseInt(pathLength);
      } catch (NumberFormatException e) {
        // nothing
      }
    }
  }

  private final Map<GraphModel.Artifact, List<GraphModel.Artifact>> sinkRoots;
  private final GraphModel.Artifact sharedNode;
  private final GraphModel.Artifact libToUpdateInDepGraph;
  private final BlossomGraphCreator blossomGraphCreator;
  private final Set<GraphModel.Artifact> nodesBoundInThisQuery = new HashSet<>();
  private final String targetVersion;
  private final ShortestPathAlgorithm<GraphModel.Artifact, GraphModel.Dependency> shortestPath;

  public SinkRootQuery(
      Map<GraphModel.Artifact, List<GraphModel.Artifact>> sinkRoots,
      GraphModel.Artifact sharedNode,
      GraphModel.Artifact libToUpdateInDepGraph,
      BlossomGraphCreator blossomGraphCreator,
      String targetVersion,
      ShortestPathAlgorithm<GraphModel.Artifact, GraphModel.Dependency> shortestPath) {
    this.sinkRoots = sinkRoots;
    this.sharedNode = sharedNode;
    this.libToUpdateInDepGraph = libToUpdateInDepGraph;
    this.blossomGraphCreator = blossomGraphCreator;
    this.targetVersion = targetVersion;
    this.shortestPath = shortestPath;
  }

  public Map<GraphModel.Artifact, List<GraphModel.Artifact>> getSinkRoots() {
    return sinkRoots;
  }

  public GraphModel.Artifact getSharedNode() {
    return sharedNode;
  }

  public String generateQuery(Collection<GraphModel.Artifact> boundNodes) {

    if (sinkRoots.size() == 1) {

      GraphModel.Artifact rootNode = (GraphModel.Artifact) sinkRoots.keySet().toArray()[0];
      if ((rootNode == libToUpdateInDepGraph
              || blossomGraphCreator.isBlossomNode(rootNode, sharedNode))
          && sharedNode == libToUpdateInDepGraph) {
        // get the subgraph for the node to update, as it is a root node
        return String.format(
            "MATCH %2$s = ((%1$s:MvnArtifact)-[:DEPENDS_ON*0..3 {scope:\"COMPILE\"}]->(%3$s:MvnArtifact))",
            Utils.getNodeNameForCypher(libToUpdateInDepGraph),
            Utils.getPathName(libToUpdateInDepGraph, null),
            Utils.getNodeNameForCypher(null));
      } else if (sharedNode == libToUpdateInDepGraph) {

        final Collection<GraphModel.Artifact> artifacts =
            blossomGraphCreator.expandBlossomNode(rootNode);
        int pathLength = Integer.MAX_VALUE;

        if (artifacts != null && !artifacts.isEmpty()) {
          // we have a blossom node, choose one that is an actual parent and that has the shortest
          // path, choose one
          // that has the shortest path
          // find the shortest
          for (GraphModel.Artifact artifact : artifacts) {
            final GraphPath<GraphModel.Artifact, GraphModel.Dependency> path =
                shortestPath.getPath(artifact, sharedNode);
            if (path != null) {
              int curPath = path.getLength();
              if (curPath < pathLength) {
                pathLength = curPath;
                rootNode = artifact;
              }
            }
          }
          if (pathLength == Integer.MAX_VALUE) {
            System.out.println("Error path not found");
          }
        }
        if (pathLength == Integer.MAX_VALUE) {
          // default to
          pathLength = 3;
        }
        //  -- avoid duplicate here with LIMIT 1
        // generate easy match subgraph
        final String pathName = Utils.getPathName(rootNode, libToUpdateInDepGraph);
        final String format =
            String.format(
                "MATCH %2$s = ((%1$s:MvnArtifact)-[:DEPENDS_ON*0..%4$s {scope:\"COMPILE\"}]->(%3$s:MvnArtifact))",
                Utils.getNodeNameForCypher(rootNode),
                pathName,
                Utils.getNodeNameForCypher(libToUpdateInDepGraph),
                Math.max(pathLength, MIN_PATH_LENGTH));

        String whereExpression;
        if (boundNodes.contains(rootNode)) {
          // leave it, no where-expr necessary
          whereExpression = "";
        } else {
          // for the shared node
          whereExpression =
              String.format(
                  "%1$s.group=\"%2$s\" AND  %1$s.artifact=\"%3$s\" AND %1$s.version >= \"%4$s\" ",
                  Utils.getNodeNameForCypher(rootNode),
                  rootNode.getGroupId(),
                  rootNode.getArtifactId(),
                  rootNode.getVersion());
          nodesBoundInThisQuery.add(sharedNode);
        }

        return "CALL{ "
            + "WITH "
            + Utils.getNodeNameForCypher(sharedNode)
            + "\n"
            + format
            + "\n"
            + ((StringUtils.isNotBlank(whereExpression)) ? " WHERE " + whereExpression : "")
            + "\n"
            + " RETURN "
            + pathName
            + " LIMIT 1 } \n";

      } else {
        return "";
      }

    } else if (sinkRoots.size() > 1) {
      String sharedNodeName = Utils.getNodeNameForCypher(sharedNode);

      Set<GraphModel.Artifact> rootNodesToCreateConstFor = new HashSet<>();
      final Map<String, List<GraphModel.Artifact>> blossomsSameGroup =
          sinkRoots.keySet().stream().collect(groupingBy(GraphModel.Artifact::getGroupId));
      // select the first per group only
      List<GraphModel.Artifact> relevantSinkRoots = new ArrayList<>();
      for (Map.Entry<String, List<GraphModel.Artifact>> entry : blossomsSameGroup.entrySet()) {
        relevantSinkRoots.add(
            entry.getValue().stream().min(Comparator.comparing(GraphModel.Artifact::toGav)).get());
      }

      // only choose one of a blossom node
      for (GraphModel.Artifact rNode : relevantSinkRoots) {
        // skip the shared node constraints for blossoms
        if (blossomGraphCreator.isBlossomNode(rNode, sharedNode)) {
          continue;
        }
        final Collection<GraphModel.Artifact> artifacts =
            blossomGraphCreator.expandBlossomNode(rNode);
        if (artifacts != null && !artifacts.isEmpty()) {
          // we have a blossom node, choose one that is an actual parent and that has the shortest
          // path, choose one
          // that has the shortest path
          // find the shortest
          int pathLength = Integer.MAX_VALUE;
          for (GraphModel.Artifact artifact : artifacts) {
            final GraphPath<GraphModel.Artifact, GraphModel.Dependency> path =
                shortestPath.getPath(artifact, sharedNode);
            if (path != null) {
              int curPath = path.getLength();
              if (curPath < pathLength) {
                pathLength = curPath;
                rNode = artifact;
              }
            }
          }
          if (pathLength == Integer.MAX_VALUE) {
            System.out.println("Error path not found");
          }
        }
        rootNodesToCreateConstFor.add(rNode);
      }
      if (rootNodesToCreateConstFor.isEmpty()) {
        // nothing to do
        return "";
      }
      // create the constraints
      Map<String, String> pathNameAndExpression = new HashMap<>();
      for (GraphModel.Artifact rNode : rootNodesToCreateConstFor) {
        // use the path as an initial length
        String pathName = Utils.getPathName(rNode, sharedNode);
        int pathLength = 3;
        final GraphPath<GraphModel.Artifact, GraphModel.Dependency> path =
            shortestPath.getPath(rNode, sharedNode);
        if (path != null) {
          pathLength = path.getLength();
        } else {
          System.err.println("NO PATH FOUND");
        }
        String expression =
            String.format(
                "%1$s = ( (%2$s:MvnArtifact)-[:DEPENDS_ON*0..%4$s {scope:\"COMPILE\"}]->(%3$s:MvnArtifact) )",
                pathName,
                Utils.getNodeNameForCypher(rNode),
                sharedNodeName,
                Math.max(pathLength, MIN_PATH_LENGTH));
        pathNameAndExpression.put(pathName, expression);
      }
      List<String> nodeWhereConditions = new ArrayList<>();
      List<String> importStatements = new ArrayList<>();

      if (boundNodes.contains(sharedNode)) {
        // import it
        importStatements.add(sharedNodeName);
      } else {
        // for the shared node
        String whereSharedNode =
            String.format(
                "%1$s.group = \"%2$s\" AND %1$s.artifact = \"%3$s\" ",
                sharedNodeName, sharedNode.getGroupId(), sharedNode.getArtifactId());

        if (blossomGraphCreator.isBlossomNode(libToUpdateInDepGraph, sharedNode)) {
          // use the same targetVersion
          whereSharedNode =
              whereSharedNode
                  + String.format("AND %1$s.version=\"%2$s\"", sharedNodeName, targetVersion);
        }

        nodeWhereConditions.add(whereSharedNode);
        nodesBoundInThisQuery.add(sharedNode);
      }

      for (GraphModel.Artifact rNode : rootNodesToCreateConstFor) {
        if (boundNodes.contains(rNode)) {
          // import it
          importStatements.add(Utils.getNodeNameForCypher(rNode));
        } else {
          // IMPORTANT INFO: using a version constraint >= the current version heavily improves
          // speed on neo4j
          // lookup, since it filters the starting nodes
          String whereExpression =
              String.format(
                  "%1$s.group=\"%2$s\" AND  %1$s.artifact=\"%3$s\" ",
                  Utils.getNodeNameForCypher(rNode), rNode.getGroupId(), rNode.getArtifactId());

          if (blossomGraphCreator.isBlossomNode(libToUpdateInDepGraph, rNode)) {
            // use the same targetVersion
            whereExpression =
                whereExpression
                    + String.format(
                        "AND %1$s.version=\"%2$s\"",
                        Utils.getNodeNameForCypher(rNode), targetVersion);
          } else {
            whereExpression =
                whereExpression
                    + String.format(
                        "AND %1$s.version >= \"%2$s\"",
                        Utils.getNodeNameForCypher(rNode), rNode.getVersion());
          }

          nodeWhereConditions.add(whereExpression);
          nodesBoundInThisQuery.add(rNode);
        }
      }
      // create the final with-clause

      final List<String> finalWithClause = new ArrayList<>();
      finalWithClause.addAll(pathNameAndExpression.keySet());
      finalWithClause.addAll(
          nodesBoundInThisQuery.stream()
              .filter(x -> !importStatements.contains(Utils.getNodeNameForCypher(x)))
              .map(Utils::getNodeNameForCypher)
              .collect(Collectors.toSet()));

      return "CALL{ "
          + ((importStatements.size() > 0) ? ("WITH " + String.join(", ", importStatements)) : "")
          + " MATCH "
          + String.join(", ", pathNameAndExpression.values())
          + "\n"
          + ((nodeWhereConditions.size() > 0)
              ? " WHERE " + String.join(" AND ", nodeWhereConditions)
              : "")
          + "\n"
          + " "
          + "WITH DISTINCT "
          + sharedNodeName
          + ", " // the shared nodeName is above, thus remove here
          + String.join(
              " , ",
              finalWithClause.stream()
                  .filter(x -> !StringUtils.equals(x, sharedNodeName))
                  .collect(Collectors.toList()))
          + "\n "
          + " RETURN "
          + String.join(", ", finalWithClause)
          + " LIMIT 1 } \n";
    } else {
      return "";
    }
  }

  @Override
  public Collection<GraphModel.Artifact> getBoundNodes() {
    return nodesBoundInThisQuery;
  }

  public String getSubGraph(Collection<GraphModel.Artifact> boundNodes) {
    String ret = "";
    final Set<GraphModel.Artifact> roots =
        this.nodesBoundInThisQuery.stream() // no subgraph for the shared node required
            .filter(x -> x != this.sharedNode)
            .collect(Collectors.toSet());

    if (!roots.isEmpty()) {
      List<String> importStatements = new ArrayList<>();
      List<String> finalNames = new ArrayList<>();

      // use WITH and LIMIT 10 -- to improve performance here
      for (GraphModel.Artifact root : roots) {

        if (boundNodes.contains(root)) {
          // import it
          importStatements.add(Utils.getNodeNameForCypher(root));
        }
        final String pathName = Utils.getPathName(root, null);
        final String nodeNameForCypher = Utils.getNodeNameForCypher(root);
        finalNames.add(pathName);
        ret =
            ret
                + "\n"
                + String.format(
                    "MATCH %1$s = ((%2$s:MvnArtifact)-[:DEPENDS_ON*0..3 {scope:\"COMPILE\"}]->(:MvnArtifact))",
                    pathName, nodeNameForCypher);
      }

      return "CALL{ "
          + ((importStatements.size() > 0) ? ("WITH " + String.join(", ", importStatements)) : "")
          + "\n"
          + ret
          + "\n"
          + " RETURN "
          + String.join(", ", finalNames)
          + " LIMIT "
          + SUBGRAPH_LIMIT
          + "}";
    }
    return ret;
  }
}
