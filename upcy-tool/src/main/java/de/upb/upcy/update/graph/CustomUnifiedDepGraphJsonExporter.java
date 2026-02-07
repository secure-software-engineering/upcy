package de.upb.upcy.update.graph;

import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.json.JSONExporter;
import soot.SootMethod;
import soot.jimple.toolkits.callgraph.Edge;

import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JSONExporter for graphs with String vertices and CustomEdge edges.
 * Exports the graph structure along with the custom edge data.
 */
public class CustomUnifiedDepGraphJsonExporter {

  private final JSONExporter<String, CustomEdge> exporter;

  public CustomUnifiedDepGraphJsonExporter() {
    this.exporter = new JSONExporter<>();
    configureExporter();
  }

  /**
   * Configure the JSONExporter with vertex and edge providers
   */
  private void configureExporter() {
    // Vertex ID provider - uses the String vertex value as-is
    exporter.setVertexIdProvider(v -> v);

    // Edge ID provider - creates unique IDs for edges
    exporter.setEdgeIdProvider(edge -> String.valueOf(System.identityHashCode(edge)));

    // Edge attribute provider - extracts CustomEdge data
    exporter.setEdgeAttributeProvider(edge -> {
      Map<String, Attribute> attributes = new LinkedHashMap<>();

      // Add the srcTgtMethods list as a JSON array
      if (edge.getSrcTgtMethods() != null && !edge.getSrcTgtMethods().isEmpty()) {
        StringBuilder methodsJson = new StringBuilder("[");

        for (int i = 0; i < edge.getSrcTgtMethods().size(); i++) {
          Pair<SootMethod, SootMethod> pair = edge.getSrcTgtMethods().get(i);

          if (i > 0) {
            methodsJson.append(",");
          }

          methodsJson.append("{");
          methodsJson.append("\"source\":\"")
              .append(escapeJson(getMethodSignature(pair.getLeft())))
              .append("\",");
          methodsJson.append("\"target\":\"")
              .append(escapeJson(getMethodSignature(pair.getRight())))
              .append("\"");
          methodsJson.append("}");
        }

        methodsJson.append("]");
        attributes.put("srcTgtMethods", DefaultAttribute.createAttribute(methodsJson.toString()));
      }

      return attributes;
    });
  }

  /**
   * Export the graph to a JSON string
   */
  public String exportToString(Graph<String, CustomEdge> graph) {
    StringWriter writer = new StringWriter();
    exporter.exportGraph(graph, writer);
    return writer.toString();
  }

  /**
   * Export the graph to a Writer
   */
  public void exportGraph(Graph<String, CustomEdge> graph, Writer writer) {
    exporter.exportGraph(graph, writer);
  }

  /**
   * Get method signature as string
   */
  private String getMethodSignature(SootMethod method) {
    if (method == null) {
      return "null";
    }
    return method.getSignature();
  }

  /**
   * Escape special characters for JSON
   */
  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }


}