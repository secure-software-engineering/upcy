package de.upb.upcy.update.recommendation;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.jgrapht.graph.AbstractBaseGraph;
import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.util.SupplierUtil;
import soot.MethodOrMethodContext;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

/**
 * Utility class to use Soot's call graph with the jGraphT library *
 *
 * @author adann Created by adann on 25.10.16.
 */
public class SootCallGraphAdapter extends AbstractBaseGraph<Object, Edge> {

  private final CallGraph callGraph;
  private Set<Object> methodVertexSet;

  public SootCallGraphAdapter(CallGraph callGraph) {
    super(
        null,
        SupplierUtil.createSupplier(Edge.class),
        new DefaultGraphType.Builder()
            .allowCycles(true)
            .allowSelfLoops(true)
            .allowMultipleEdges(true)
            .directed()
            .build());
    this.callGraph = callGraph;
    this.vertexSet();
  }

  public CallGraph getCallGraph() {
    return callGraph;
  }

  @Override
  public int inDegreeOf(Object obj) {

    if (obj instanceof SootMethod) {
      SootMethod method = (SootMethod) obj;
      int i = 0;
      Iterator<Edge> it = callGraph.edgesInto(method);
      while (it.hasNext()) {
        i++;
        it.next();
      }

      return i;
    } else {
      throw new RuntimeException("Not a method");
    }
  }

  @Override
  public Set<Edge> incomingEdgesOf(Object obj) {
    if (obj instanceof SootMethod) {
      SootMethod method = (SootMethod) obj;
      Set<Edge> edges = new HashSet<>();
      Iterator<Edge> it = callGraph.edgesInto(method);
      while (it.hasNext()) {
        edges.add(it.next());
      }
      return edges;
    } else if (obj instanceof Unit) {
      throw new RuntimeException("Error");
    } else {
      throw new RuntimeException("Error");
    }
  }

  @Override
  public int outDegreeOf(Object obj) {

    if (obj instanceof SootMethod) {
      SootMethod method = (SootMethod) obj;
      int i = 0;
      Iterator<Edge> it = callGraph.edgesOutOf(method);
      while (it.hasNext()) {
        i++;
        it.next();
      }

      return i;
    } else if (obj instanceof Unit) {

      Unit unit = (Unit) obj;
      int i = 0;
      Iterator<Edge> it = callGraph.edgesOutOf(unit);
      while (it.hasNext()) {
        i++;
        it.next();
      }

      return i;

    } else {
      throw new RuntimeException("Call graph adapter failed");
    }
  }

  @Override
  public Set<Edge> outgoingEdgesOf(Object obj) {

    if (obj instanceof SootMethod) {
      SootMethod method = (SootMethod) obj;
      Set<Edge> edges = new HashSet<>();
      Iterator<Edge> it = callGraph.edgesOutOf(method);
      while (it.hasNext()) {
        edges.add(it.next());
      }
      return edges;
    } else if (obj instanceof Unit) {
      Unit unit = (Unit) obj;
      Set<Edge> edges = new HashSet<>();
      Iterator<Edge> it = callGraph.edgesOutOf(unit);
      while (it.hasNext()) {
        edges.add(it.next());
      }
      return edges;
    } else {
      throw new RuntimeException("awdwa");
    }
  }

  @Override
  public Set<Edge> getAllEdges(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public Edge getEdge(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public Edge addEdge(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean addEdge(Object method, Object v1, Edge edge) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean addVertex(Object method) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean containsEdge(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean containsEdge(Edge edge) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean containsVertex(Object method) {
    return methodVertexSet.contains(method);
  }

  @Override
  public Set<Edge> edgeSet() {
    //  callGraph.getEdges();
    return null;
  }

  @Override
  public Set<Edge> edgesOf(Object method) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean removeAllEdges(Collection<? extends Edge> collection) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public Set<Edge> removeAllEdges(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean removeAllVertices(Collection<?> collection) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public Edge removeEdge(Object method, Object v1) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean removeEdge(Edge edge) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public boolean removeVertex(Object method) {
    throw new RuntimeException("Unsupported Operation");
  }

  @Override
  public Set<Object> vertexSet() {
    if (methodVertexSet != null) {
      return methodVertexSet;
    }
    methodVertexSet = new HashSet<>();
    Iterator<MethodOrMethodContext> it = callGraph.sourceMethods();
    while (it.hasNext()) {
      MethodOrMethodContext m = it.next();
      methodVertexSet.add(m.method());
      methodVertexSet.add(m.context());
    }

    return methodVertexSet;
  }

  @Override
  public SootMethod getEdgeSource(Edge edge) {
    return edge.src();
  }

  @Override
  public SootMethod getEdgeTarget(Edge edge) {
    return edge.tgt();
  }

  @Override
  public double getEdgeWeight(Edge edge) {
    return 0;
  }
}
