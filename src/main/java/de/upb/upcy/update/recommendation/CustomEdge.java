package de.upb.upcy.update.recommendation;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import soot.SootMethod;

/** Edge in the unified dependency graph, containing the call edges source and targets */
public class CustomEdge {

  private final List<Pair<SootMethod, SootMethod>> srcTgtMethods = new ArrayList<>();

  public List<Pair<SootMethod, SootMethod>> getSrcTgtMethods() {
    return srcTgtMethods;
  }
}
