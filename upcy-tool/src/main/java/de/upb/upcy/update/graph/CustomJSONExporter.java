package de.upb.upcy.update.graph;


import org.apache.commons.text.*;
import org.jgrapht.*;
import org.jgrapht.nio.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import org.jgrapht.nio.json.JSONExporter;


public class CustomJSONExporter<V, E>
    extends
    JSONExporter<V, E> {

  private static final String CREATOR = "JGraphT Custom JSON Exporter";
  private static final String VERSION = "1";

  /**
   * Creates a new exporter with integers for the vertex identifiers.
   */
  public CustomJSONExporter() {
    this(new IntegerIdProvider<>());
  }

  /**
   * Creates a new exporter.
   *
   * @param vertexIdProvider for generating vertex identifiers. Must not be null.
   */
  public CustomJSONExporter(Function<V, String> vertexIdProvider) {
    super(vertexIdProvider);
  }

  @Override
  public void exportGraph(Graph<V, E> g, Writer writer) {
    PrintWriter out = new PrintWriter(writer);

    out.print('{');

    /*
     * Version
     */
    out.print(quoted("creator"));
    out.print(':');
    out.print(quoted(CREATOR));

    out.print(',');
    out.print(quoted("version"));
    out.print(':');
    out.print(quoted(VERSION));

    /*
     * Vertices
     */
    out.print(',');
    out.print(quoted("nodes"));
    out.print(':');
    out.print('[');
    boolean printComma = false;
    for (V v : g.vertexSet()) {
      if (!printComma) {
        printComma = true;
      } else {
        out.print(',');
      }
      exportVertex(out, g, v);
    }
    out.print("]");

    /*
     * Edges
     */
    out.print(',');
    out.print(quoted("edges"));
    out.print(':');
    out.print('[');
    printComma = false;
    for (E e : g.edgeSet()) {
      if (!printComma) {
        printComma = true;
      } else {
        out.print(',');
      }
      exportEdge(out, g, e);
    }
    out.print("]");

    out.print('}');

    out.flush();
  }

  private void exportVertex(PrintWriter out, Graph<V, E> g, V v) {
    String vertexId = vertexIdProvider.apply(v);

    out.print('{');
    out.print(quoted("id"));
    out.print(':');
    out.print(quoted(vertexId));
    exportVertexAttributes(out, g, v);
    out.print('}');
  }

  private void exportEdge(PrintWriter out, Graph<V, E> g, E e) {
    V source = g.getEdgeSource(e);
    String sourceId = vertexIdProvider.apply(source);
    V target = g.getEdgeTarget(e);
    String targetId = vertexIdProvider.apply(target);

    out.print('{');

    edgeIdProvider.ifPresent(p -> {
      String edgeId = p.apply(e);
      if (edgeId != null) {
        out.print(quoted("id"));
        out.print(':');
        out.print(quoted(edgeId));
        out.print(',');
      }
    });

    out.print(quoted("source"));
    out.print(':');
    out.print(quoted(sourceId));
    out.print(',');
    out.print(quoted("target"));
    out.print(':');
    out.print(quoted(targetId));

    exportEdgeAttributes(out, g, e);

    out.print('}');
  }

  private void exportVertexAttributes(PrintWriter out, Graph<V, E> g, V v) {
    if (!vertexAttributeProvider.isPresent()) {
      return;
    }
    vertexAttributeProvider
        .get().apply(v).entrySet().stream().filter(e -> !e.getKey().equals("id"))
        .forEach(entry -> {
          out.print(",");
          out.print(quoted(entry.getKey()));
          out.print(":");
          outputValue(out, entry.getValue());
        });
  }

  private void exportEdgeAttributes(PrintWriter out, Graph<V, E> g, E e) {
    if (!edgeAttributeProvider.isPresent()) {
      return;
    }
    Set<String> forbidden = new HashSet<>(Arrays.asList("id", "source", "target"));
    edgeAttributeProvider
        .get().apply(e).entrySet().stream().filter(entry -> !forbidden.contains(entry.getKey()))
        .forEach(entry -> {
          out.print(",");
          out.print(quoted(entry.getKey()));
          out.print(":");
          outputValue(out, entry.getValue());
        });
  }

  private void outputValue(PrintWriter out, Attribute value) {
    AttributeType type = value.getType();
    if (type.equals(AttributeType.BOOLEAN)) {
      boolean booleanValue = Boolean.parseBoolean(value.getValue());
      out.print(booleanValue ? "true" : "false");
    } else if (type.equals(AttributeType.INT)) {
      out.print(Integer.parseInt(value.getValue()));
    } else if (type.equals(AttributeType.LONG)) {
      out.print(Long.parseLong(value.getValue()));
    } else if (type.equals(AttributeType.FLOAT)) {
      float floatValue = Float.parseFloat(value.getValue());
      if (!Float.isFinite(floatValue)) {
        throw new IllegalArgumentException("Infinity and NaN not allowed in JSON");
      }
      out.print(floatValue);
    } else if (type.equals(AttributeType.DOUBLE)) {
      double doubleValue = Double.parseDouble(value.getValue());
      if (!Double.isFinite(doubleValue)) {
        throw new IllegalArgumentException("Infinity and NaN not allowed in JSON");
      }
      out.print(doubleValue);
    } else if (type.equals(AttributeType.HTML)) {
      out.print(value.toString());

    } else {
      out.print(quoted(value.toString()));
    }
  }

  private String quoted(final String s) {
    return "\"" + StringEscapeUtils.escapeJson(s) + "\"";
  }

}
