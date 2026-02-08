package de.upb.upcy.update.graph;

import de.upb.upcy.base.graph.GraphModel.Artifact;
import java.util.HashMap;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.AttributeType;
import org.jgrapht.nio.DefaultAttribute;
import soot.SootMethod;

import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSONExporter for graphs with String vertices and CustomEdge edges. Exports the graph structure
 * along with the custom edge data.
 */
public class CustomUnifiedDepGraphJSONExporter {

  private final CustomJSONExporter<String, CustomEdge> exporter;
  private final HashMap<String, Artifact> unifiedDepVertexToArtifact;

  public CustomUnifiedDepGraphJSONExporter(HashMap<String, Artifact> unifiedDepVertexToArtifact) {
    this.unifiedDepVertexToArtifact = unifiedDepVertexToArtifact;
    this.exporter = new CustomJSONExporter<>();
    configureExporter();
  }

  /**
   * Configure the JSONExporter with vertex and edge providers
   */
  private void configureExporter() {
    // Vertex ID provider - uses the String vertex value as-is
    exporter.setVertexIdProvider(v -> v);

    exporter.setVertexAttributeProvider(v -> {
      Map<String, Attribute> attributes = new LinkedHashMap<>();

      StringBuilder jsonArray = new StringBuilder("[");

      Artifact artifact = this.unifiedDepVertexToArtifact.get(v);
      for (int i = 0; i < artifact.getScopes().size(); i++) {
        String scope = artifact.getScopes().get(i);

        if (i > 0) {
          jsonArray.append(",");
        }

        jsonArray.append("\"" + scope + "\"");

      }
      jsonArray.append("]");

      // Use a custom attribute that returns raw JSON
      attributes.put("scope", new RawJsonAttribute(jsonArray.toString()));
      return attributes;

    });

    // Edge ID provider - creates unique IDs for edges
    exporter.setEdgeIdProvider(edge -> String.valueOf(System.identityHashCode(edge)));

    // Edge attribute provider - extracts CustomEdge data
    exporter.setEdgeAttributeProvider(edge -> {
      Map<String, Attribute> attributes = new LinkedHashMap<>();

      // Add the srcTgtMethods list as a proper JSON array
      if (edge.getSrcTgtMethods() != null && !edge.getSrcTgtMethods().isEmpty()) {
        // Build JSON array manually as a string
        StringBuilder jsonArray = new StringBuilder("[");

        for (int i = 0; i < edge.getSrcTgtMethods().size(); i++) {
          Pair<SootMethod, SootMethod> pair = edge.getSrcTgtMethods().get(i);

          if (i > 0) {
            jsonArray.append(",");
          }

          jsonArray.append("{");
          jsonArray.append("\"source\":\"")
              .append(escapeJson(getMethodSignature(pair.getLeft())))
              .append("\",");
          jsonArray.append("\"target\":\"")
              .append(escapeJson(getMethodSignature(pair.getRight())))
              .append("\"");
          jsonArray.append("}");
        }

        jsonArray.append("]");

        // Use a custom attribute that returns raw JSON
        attributes.put("srcTgtMethods", new RawJsonAttribute(jsonArray.toString()));
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

  /**
   * Custom Attribute implementation for raw JSON values This ensures the JSON array is not
   * double-quoted
   */
  private static class RawJsonAttribute implements Attribute {

    private final String jsonValue;

    public RawJsonAttribute(String jsonValue) {
      this.jsonValue = jsonValue;
    }

    @Override
    public String getValue() {
      return jsonValue;
    }

    @Override
    public AttributeType getType() {
      return AttributeType.HTML; // Setting to "html" prevents JGraphT from quoting it
    }

    @Override
    public String toString() {
      return jsonValue;
    }
  }


}