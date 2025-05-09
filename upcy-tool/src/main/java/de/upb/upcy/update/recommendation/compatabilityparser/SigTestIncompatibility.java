package de.upb.upcy.update.recommendation.compatabilityparser;

import java.util.List;
import lombok.Data;
import org.jetbrains.annotations.Nullable;

@Data
public class SigTestIncompatibility implements Incompatibility {
  // Null indicates that no fields or methods are change
  @Nullable private final List<String> methodNames;
  @Nullable private final List<String> fieldNames;
  @Nullable private final List<String> interfaceNames;
  String className;

  public SigTestIncompatibility(
      String className,
      @Nullable List<String> methodNames,
      @Nullable List<String> fieldNames,
      @Nullable List<String> interfaceNames) {
    this.className = className;
    this.methodNames = methodNames;
    this.fieldNames = fieldNames;
    this.interfaceNames = interfaceNames;
  }

  public String getClassName() {
    return className;
  }

  public @Nullable List<String> getMethodNames() {
    return methodNames;
  }

  public @Nullable List<String> getFieldNames() {
    return fieldNames;
  }

  public @Nullable List<String> getInterfaceNames() {
    return interfaceNames;
  }
}
