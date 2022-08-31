package de.upb.upcy.update.recommendation.check;

import lombok.EqualsAndHashCode;
import lombok.Value;
import soot.SootMethod;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Value
public class ForwardViolation extends Violation {

  public ForwardViolation(List<SootMethod> violatedCalls, String orgLib, String updatedLib) {
    super(violatedCalls, orgLib, updatedLib);
  }
}
