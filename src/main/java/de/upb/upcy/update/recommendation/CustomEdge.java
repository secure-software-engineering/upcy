package de.upb.upcy.update.recommendation;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import soot.SootMethod;

public class CustomEdge {

  public List<Pair<SootMethod, SootMethod>> getSrcTgtMethods() {
    return srcTgtMethods;
  }

  private final List<Pair<SootMethod, SootMethod>> srcTgtMethods = new ArrayList<>();
}
