package de.upb.upcy.update.recommendation.cypher;

import de.upb.upcy.base.graph.GraphModel;
import java.util.Collection;
import java.util.Collections;

public class MatchUpdateNodeQuery implements CypherQuery {

  private final GraphModel.Artifact libToUpdateInDepGraph;
  private final String targetVersion;

  public MatchUpdateNodeQuery(GraphModel.Artifact libToUpdateInDepGraph, String targetVersion) {
    this.libToUpdateInDepGraph = libToUpdateInDepGraph;
    this.targetVersion = targetVersion;
  }

  public String generateQuery(Collection<GraphModel.Artifact> boundNodes) {
    // 1. the constraint for the library to update
    return String.format(
        "MATCH (%1$s:MvnArtifact) where %1$s.group=\"%2$s\" and %1$s.artifact=\"%3$s\" and %1$s.version=\"%4$s\" and %1$s.classifier=\"null\"",
        Utils.getNodeNameForCypher(libToUpdateInDepGraph),
        libToUpdateInDepGraph.getGroupId(),
        libToUpdateInDepGraph.getArtifactId(),
        targetVersion);
  }

  @Override
  public Collection<GraphModel.Artifact> getBoundNodes() {
    return Collections.singletonList(libToUpdateInDepGraph);
  }
}
