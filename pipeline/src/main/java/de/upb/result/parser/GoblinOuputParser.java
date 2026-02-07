package de.upb.result.parser;

import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GoblinOuputParser {

  public static class GoblinUpdateEdge {

    @CsvBindByName(column = "startNode")
    private String startNode;

    @CsvBindByName(column = "targetNode")
    private String targetNode;

    @CsvBindByName(column = "projectName")
    private String projectName;

    public String getProjectDir() {
      return projectDir;
    }

    public void setProjectDir(String projectDir) {
      this.projectDir = projectDir;
    }

    @CsvBindByName(column = "projectDir")
    private String projectDir;

    public GoblinUpdateEdge(String startNode, String targetNode) {
      this.startNode = startNode;
      this.targetNode = targetNode;
    }

    public String getStartNode() {
      return startNode;
    }

    public String getTargetNode() {
      return targetNode;
    }

    @Override
    public String toString() {
      return "StartNode: " + startNode + " -> TargetNode: " + targetNode;
    }

    public String getProjectName() {
      return projectName;
    }

    public void setProjectName(String projectName) {
      this.projectName = projectName;
    }
  }

  public static List<GoblinUpdateEdge> parseLogFile(String filePath) throws IOException {
    List<GoblinUpdateEdge> edges = new ArrayList<>();

    // Pattern to match lines like: ROOT==>com.google.code.findbugs:annotations:3.0.1 (e62) : 1.0
    Pattern pattern = Pattern.compile("^\\[INFO\\s*\\].*?([A-Za-z0-9_]+)==>([^\\s]+).*$");

    try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
          String startNode = matcher.group(1);
          String targetNode = matcher.group(2);
          edges.add(new GoblinUpdateEdge(startNode, targetNode));
        }
      }
    }

    return edges;
  }

  public static void writeResultsToCsv(String filePath, List<GoblinUpdateEdge> updateEdgeList)
      throws IOException, CsvRequiredFieldEmptyException, CsvDataTypeMismatchException {

    try (FileWriter writer = new FileWriter(filePath)) {
      StatefulBeanToCsv<GoblinUpdateEdge> beanToCsv =
          new StatefulBeanToCsvBuilder<GoblinUpdateEdge>(writer)
              .withApplyQuotesToAll(false) // optional
              .build();

      beanToCsv.write(updateEdgeList);
    }
  }

  public static void main(String[] args) {
    try {

      String projectPath = ".";

      // Parse command line arguments
      if (args.length > 0) {
        projectPath = args[0];
      } else {
        throw new IllegalArgumentException("Missing project path");
      }
      String goblinUpdater = "GoblinUpdater";
      Path resultsFolder = Paths.get(projectPath).resolve(goblinUpdater);
      Set<Path> logfiles = new HashSet<>();
      // find the files
      try (Stream<Path> stream = Files.walk(resultsFolder)) {
        logfiles =
            stream
                .filter(
                    file ->
                        Files.isRegularFile(file) && file.getFileName().toString().endsWith(".log"))
                .collect(Collectors.toSet());
      }
      List<GoblinUpdateEdge> buildResultList = new ArrayList<>();
      for (Path logfile : logfiles) {
        List<GoblinUpdateEdge> buildResult = parseLogFile(logfile.toAbsolutePath().toString());
        buildResult.forEach(
            x -> {
              x.setProjectName(
                  logfile.getFileName().toString().replace("_", ":").replace(".log", ""));
              x.setProjectDir(logfile.getParent().getFileName().toString());
            });
        buildResultList.addAll(buildResult);
      }

      writeResultsToCsv(
          Paths.get(projectPath)
              .resolve("results_" + goblinUpdater + ".csv")
              .toAbsolutePath()
              .toString(),
          buildResultList);

    } catch (Exception e) {
      System.err.println("Error " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}
