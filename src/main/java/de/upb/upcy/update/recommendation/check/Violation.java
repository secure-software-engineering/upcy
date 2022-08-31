package de.upb.upcy.update.recommendation.check;

import lombok.AllArgsConstructor;
import lombok.Data;
import soot.SootMethod;

import java.util.List;

@AllArgsConstructor
@Data
public class Violation {
  private final List<SootMethod> violatedCalls;
  private final String orgLib;
  private final String updatedLib;
}
