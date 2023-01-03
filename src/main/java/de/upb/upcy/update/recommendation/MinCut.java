package de.upb.upcy.update.recommendation;

import de.upb.upcy.base.graph.GraphModel;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Represent a computed min-(s,t)-cut */
@Data
@AllArgsConstructor
public class MinCut {

  private double weight;
  private Set<GraphModel.Dependency> edges;
  private Set<GraphModel.Artifact> sink;
  private Set<GraphModel.Artifact> source;
}
