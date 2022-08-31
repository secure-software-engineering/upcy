package de.upb.upcy.update.recommendation.cypher;

import de.upb.upcy.base.graph.GraphModel;
import de.upb.upcy.update.recommendation.BlossomGraphCreator;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

public class SinkRootQuery implements CypherQuery {

  private final Map<GraphModel.Artifact, List<GraphModel.Artifact>> sinkRoots;
  private final GraphModel.Artifact sharedNode;
  private final GraphModel.Artifact libToUpdateInDepGraph;
  private final BlossomGraphCreator blossomGraphCreator;
  private final Set<GraphModel.Artifact> nodesBoundInThisQuery = new HashSet<>();
  private final String targetVersion;

  public SinkRootQuery(
      Map<GraphModel.Artifact, List<GraphModel.Artifact>> sinkRoots,
      GraphModel.Artifact sharedNode,
      GraphModel.Artifact libToUpdateInDepGraph,
      BlossomGraphCreator blossomGraphCreator,
      String targetVersion) {
    this.sinkRoots = sinkRoots;
    this.sharedNode = sharedNode;
    this.libToUpdateInDepGraph = libToUpdateInDepGraph;
    this.blossomGraphCreator = blossomGraphCreator;
    this.targetVersion = targetVersion;
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
        if (artifacts != null && !artifacts.isEmpty()) {
          // we have a blossom node, select one by random --> we choose the first
          rootNode = (GraphModel.Artifact) artifacts.toArray()[0];
        }
        // generate easy match subgraph
        return String.format(
            "MATCH %2$s = ((%1$s:MvnArtifact)-[:DEPENDS_ON*0..3 {scope:\"COMPILE\"}]->(%3$s:MvnArtifact))",
            Utils.getNodeNameForCypher(rootNode),
            Utils.getPathName(rootNode, libToUpdateInDepGraph),
            Utils.getNodeNameForCypher(libToUpdateInDepGraph));
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
          // we have a blossom node, select one by random --> we choose the first
          rNode = (GraphModel.Artifact) artifacts.toArray()[0];
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
        String pathName = Utils.getPathName(rNode, sharedNode);
        String expression =
            String.format(
                "%1$s = ( (%2$s:MvnArtifact)-[:DEPENDS_ON*0..3 {scope:\"COMPILE\"}]->(%3$s:MvnArtifact) )",
                pathName, Utils.getNodeNameForCypher(rNode), sharedNodeName);
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
                  "%1$s.group=\"%2$s\" AND  %1$s.artifact=\"%3$s\" AND %1$s.version >= \"%4$s\"",
                  Utils.getNodeNameForCypher(rNode),
                  rNode.getGroupId(),
                  rNode.getArtifactId(),
                  rNode.getVersion());

          if (blossomGraphCreator.isBlossomNode(libToUpdateInDepGraph, rNode)) {
            // use the same targetVersion
            whereExpression =
                whereExpression
                    + String.format(
                        "AND %1$s.version=\"%2$s\"",
                        Utils.getNodeNameForCypher(rNode), targetVersion);
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

      return String.format(
          "CALL{ "
              + ((importStatements.size() > 0)
                  ? ("WITH " + String.join(", ", importStatements))
                  : "")
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
              + " LIMIT 1 } \n");
    } else {
      return "";
    }
  }

  @Override
  public Collection<GraphModel.Artifact> getBoundNodes() {
    return nodesBoundInThisQuery;
  }

  public String getSubGraph() {
    String ret = "";
    final Set<GraphModel.Artifact> roots =
        this.nodesBoundInThisQuery.stream() // no subgraph for the shared node required
            .filter(x -> x != this.sharedNode)
            .collect(Collectors.toSet());
    for (GraphModel.Artifact root : roots) {
      ret =
          ret
              + "\n"
              + String.format(
                  "MATCH %1$s = ((%2$s:MvnArtifact)-[:DEPENDS_ON*0..3 {scope:\"COMPILE\"}]->(:MvnArtifact))",
                  Utils.getPathName(root, null), Utils.getNodeNameForCypher(root));
    }
    return ret;
  }
}
