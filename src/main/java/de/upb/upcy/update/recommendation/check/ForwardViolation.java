package de.upb.upcy.update.recommendation.check;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Value;
import soot.SootMethod;

@EqualsAndHashCode(callSuper = true)
@Value
public class ForwardViolation extends Violation {

  public ForwardViolation(List<SootMethod> violatedCalls, String orgLib, String updatedLib) {
    super(violatedCalls, orgLib, updatedLib);
  }
}
