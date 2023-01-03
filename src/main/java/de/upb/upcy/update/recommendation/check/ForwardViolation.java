package de.upb.upcy.update.recommendation.check;

import lombok.EqualsAndHashCode;
import lombok.Value;
import soot.SootMethod;
import soot.VoidType;

import java.util.Collections;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Value
public class ForwardViolation extends Violation {

  public ForwardViolation(List<SootMethod> violatedCalls, String orgLib, String updatedLib) {
    super(
        Collections.singletonList(
            new SootMethod(
                "FORWARD_VIOLATION_CALL", Collections.singletonList(VoidType.v()), VoidType.v())),
        orgLib,
        updatedLib);
  }
}
