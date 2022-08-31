package de.upb.upcy.update.recommendation.compatabilityparser;

import de.upb.upcy.base.sigtest.db.model.sootdiff.CallGraphCheckDoc;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class SootMethodIncompatibility extends CallGraphCheckDoc.MethodCGAPI
    implements Incompatibility {

  private final CallGraphCheckDoc.MethodCGAPI methodCGAPI;

  public SootMethodIncompatibility(CallGraphCheckDoc.MethodCGAPI methodCGAPI) {
    this.methodCGAPI = methodCGAPI;
  }

  @Override
  public String getStartMethod() {
    return methodCGAPI.getStartMethod();
  }

  @Override
  public String getChangedBodyMethod() {
    return methodCGAPI.getChangedBodyMethod();
  }
}
