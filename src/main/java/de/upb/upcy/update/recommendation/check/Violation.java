package de.upb.upcy.update.recommendation.check;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import soot.SootMethod;

@AllArgsConstructor
@Data
public class Violation {
  private final List<SootMethod> violatedCalls;
  private final String orgLib;
  private final String updatedLib;
}
