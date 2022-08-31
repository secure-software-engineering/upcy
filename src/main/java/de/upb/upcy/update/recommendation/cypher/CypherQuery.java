package de.upb.upcy.update.recommendation.cypher;

import de.upb.thetis.graph.GraphModel;
import java.util.Collection;

public interface CypherQuery {

  String generateQuery(Collection<GraphModel.Artifact> boundNodes);

  Collection<GraphModel.Artifact> getBoundNodes();
}
