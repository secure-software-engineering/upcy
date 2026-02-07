package de.upb.result.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoralOutputParser {

  private static ObjectMapper objectMapper = new ObjectMapper();

  // Data model classes
  public static class CarolDependencyUpdate {

    @CsvBindByName(column = "artifact")
    private String artifact;

    @CsvBindByName(column = "oldVersion")
    private String oldVersion;

    @CsvBindByName(column = "newVersion")
    private String newVersion;

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

    public CarolDependencyUpdate() {}

    public CarolDependencyUpdate(String artifact, String oldVersion, String newVersion) {
      this.artifact = artifact;
      this.oldVersion = oldVersion;
      this.newVersion = newVersion;
    }

    public String getArtifact() {
      return artifact;
    }

    public void setArtifact(String artifact) {
      this.artifact = artifact;
    }

    public String getOldVersion() {
      return oldVersion;
    }

    public void setOldVersion(String oldVersion) {
      this.oldVersion = oldVersion;
    }

    public String getNewVersion() {
      return newVersion;
    }

    public void setNewVersion(String newVersion) {
      this.newVersion = newVersion;
    }

    @Override
    public String toString() {
      return "Dependency{"
          + "artifact='"
          + artifact
          + '\''
          + ", oldVersion='"
          + oldVersion
          + '\''
          + ", newVersion='"
          + newVersion
          + '\''
          + '}';
    }

    public String getProjectName() {
      return projectName;
    }

    public void setProjectName(String projectName) {
      this.projectName = projectName;
    }
  }

  // Parse JSON into data model using Jackson
  public static List<CarolDependencyUpdate> parseJsonWithJackson(String jsonFilePath)
      throws IOException {
    List<CarolDependencyUpdate> dependencies = new ArrayList<>();

    JsonNode rootNode = objectMapper.readTree(new File(jsonFilePath));
    Iterator<Entry<String, JsonNode>> fields = rootNode.fields();

    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String artifact = entry.getKey();
      JsonNode versionNode = entry.getValue();

      String oldVersion = versionNode.get("old_version").asText();
      String newVersion = versionNode.get("new_version").asText();

      dependencies.add(new CarolDependencyUpdate(artifact, oldVersion, newVersion));
    }

    return dependencies;
  }

  public static void writeResultsToCsv(
      String filePath, List<CarolDependencyUpdate> carolDependencyUpdateUpdateList)
      throws IOException, CsvRequiredFieldEmptyException, CsvDataTypeMismatchException {

    try (FileWriter writer = new FileWriter(filePath)) {
      StatefulBeanToCsv<CarolDependencyUpdate> beanToCsv =
          new StatefulBeanToCsvBuilder<CarolDependencyUpdate>(writer)
              .withApplyQuotesToAll(false) // optional
              .build();

      beanToCsv.write(carolDependencyUpdateUpdateList);
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
      String carolUpdater = "Coral";
      Path resultsFolder = Paths.get(projectPath).resolve(carolUpdater);
      Set<Path> logfiles = new HashSet<>();
      // find the files
      try (Stream<Path> stream = Files.walk(resultsFolder)) {
        logfiles =
            stream
                .filter(
                    file ->
                        Files.isRegularFile(file)
                            && file.getFileName().toString().endsWith(".json"))
                .collect(Collectors.toSet());
      }
      List<CarolDependencyUpdate> buildResultList = new ArrayList<>();
      for (Path logfile : logfiles) {
        List<CarolDependencyUpdate> buildResult =
            parseJsonWithJackson(logfile.toAbsolutePath().toString());
        buildResult.forEach(
            x ->{
                x.setProjectName(
                    logfile.getFileName().toString().replace("/", ":").replace(".json", ""));
              x.setProjectDir(logfile.getParent().getFileName().toString());
            });
        buildResultList.addAll(buildResult);
      }

      writeResultsToCsv(
          Paths.get(projectPath)
              .resolve("results_" + carolUpdater + ".csv")
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
