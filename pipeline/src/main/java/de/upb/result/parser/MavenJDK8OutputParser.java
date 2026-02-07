package de.upb.result.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenJDK8OutputParser extends MavenOutputParser {

  public static void main(String[] args) {
    try {

      String projectPath = ".";

      // Parse command line arguments
      if (args.length > 0) {
        projectPath = args[0];
      } else {
        throw new IllegalArgumentException("Missing project path");
      }
      String mavenBuildAndTest = "MavenJDK8BuildAndTest";
      Path resultsFolder = Paths.get(projectPath).resolve(mavenBuildAndTest);
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

      List<BuildResult> buildResultList = new ArrayList<>();
      for (Path logfile : logfiles) {
        BuildResult buildResult = parseMavenOutput(Files.readString(logfile));
        buildResult.setProjectName(
            logfile.getFileName().toString().replace("/", ":").replace(".log", ""));
        buildResult.setProjectDir(logfile.getParent().getFileName().toString());
        buildResultList.add(buildResult);
      }

      writeResultsToCsv(
          Paths.get(projectPath)
              .resolve("results_" + mavenBuildAndTest + ".csv")
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
