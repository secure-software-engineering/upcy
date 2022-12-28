package de.upb.upcy.update.recommendation;

import de.upb.upcy.base.graph.GraphModel;
import lombok.Data;

import java.util.Set;

/** Represent a computed min-(s,t)-cut */
@Data
public class MinCut {

  private double weight;
  private Set<GraphModel> edges;
  private Set<GraphModel.Artifact> sink;
  private Set<GraphModel.Artifact> source;
}
