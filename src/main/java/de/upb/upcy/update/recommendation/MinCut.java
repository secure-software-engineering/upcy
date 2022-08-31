package de.upb.upcy.update.recommendation;

import de.upb.upcy.base.graph.GraphModel;
import java.util.Set;
import lombok.Data;

@Data
public class MinCut {

  private double weight;
  private Set<GraphModel> edges;
  private Set<GraphModel.Artifact> sink;
  private Set<GraphModel.Artifact> source;
}
