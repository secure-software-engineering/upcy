package de.upb.upcy.update.recommendation.cypher;

import de.upb.upcy.base.graph.GraphModel;
import java.util.Collection;

public interface CypherQuery {

  String generateQuery(Collection<GraphModel.Artifact> boundNodes);

  Collection<GraphModel.Artifact> getBoundNodes();
}
