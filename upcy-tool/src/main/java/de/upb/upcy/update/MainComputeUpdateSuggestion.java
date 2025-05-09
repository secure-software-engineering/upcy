package de.upb.upcy.update;

import static java.util.stream.Collectors.groupingBy;

import com.opencsv.CSVWriter;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import de.upb.upcy.base.build.Utils;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.update.build.PipelineRunner;
import de.upb.upcy.update.build.Result;
import de.upb.upcy.update.recommendation.RecommendationAlgorithm;
import de.upb.upcy.update.recommendation.UpdateSuggestion;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for running the evaluation experiments. Requires as an input the folder containing the
 * projects and _update-step.csv files.
 *
 * @author adann
 */
public class MainComputeUpdateSuggestion {

  private static final Logger LOGGER = LoggerFactory.getLogger(MainComputeUpdateSuggestion.class);

  public static void main(String[] args) throws IOException {

    if (args.length == 0) {
      LOGGER.error("No arguments given");
      return;
    }
    String rootDir = args[0];

    // get the csv file to work on
    List<Path> csvFiles = new ArrayList<>();
    try (Stream<Path> walkStream = Files.walk(Paths.get(rootDir))) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                if (StringUtils.endsWith(f.getFileName().toString(), ".csv")
                    && !StringUtils.contains(f.getFileName().toString(), "graph-analysis")
                    && !StringUtils.contains(f.getFileName().toString(), "_options-analysis.csv")
                    && !StringUtils.contains(
                        f.getFileName().toString(), "_recommendation_results.csv")) {
                  // ignore graph analysis csv file
                  csvFiles.add(f);
                }
              });
    }

    final Pair<Set<String>, Path> done = getDoneProjects("./recommendation_status");
    Set<String> doneProjects = done.getLeft();
    Path statusCacheFolder = done.getRight();

    // run in separate threads
    for (Path csvFile : csvFiles) {

      Path parentDir = csvFile.getParent();

      Path statusFile = statusCacheFolder.resolve(csvFile.getFileName().toString() + ".status");
      try {
        handleProject(csvFile, parentDir);
        Files.createFile(statusFile);
        Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        LOGGER.error("Failed to handle file: " + parentDir.getFileName(), e);
      } catch (GitAPIException e) {
        LOGGER.error("Failed to Checkout project {} with", parentDir.getFileName(), e);
      }
    }
  }

  public static void awaitTerminationAfterShutdown(ExecutorService threadPool) {
    threadPool.shutdown();
    try {
      if (!threadPool.awaitTermination(60, TimeUnit.DAYS)) {
        threadPool.shutdownNow();
      }
    } catch (InterruptedException ex) {
      threadPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public static void handleProject(Path csvFile, Path outputDir)
      throws IOException, GitAPIException {
    final Path parent = csvFile.getParent();

    final String projectName = parent.getFileName().toString();

    List<Result> results;
    try (Reader reader = Files.newBufferedReader(csvFile)) {
      CsvToBean<Result> sbc =
          new CsvToBeanBuilder<Result>(reader)
              .withType(Result.class)
              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
              .build();
      results = sbc.parse();
    }
    LOGGER.info("Successfully parsed csv file: {}", csvFile.getFileName().toString());

    // clear the module names
    results.forEach(
        y -> {
          String resultProjectName = y.getProjectName();
          if (resultProjectName.contains("projectRun")) {
            // remove it from the name
            String[] split = resultProjectName.split("_");
            final List<String> newName =
                Arrays.stream(split)
                    .filter(x -> !StringUtils.startsWith(x, "projectRun"))
                    .collect(Collectors.toList());

            y.setProjectName(String.join("_", newName));
          }
        });

    final Map<String, List<Result>> groupByModuleName =
        results.stream().collect(groupingBy(Result::getProjectName));

    LOGGER.info("Running on project: {}", projectName);

    // Checkout the PROJECT
    Path commitFile = parent.resolve("COMMIT");
    if (!Files.exists(commitFile)) {
      LOGGER.error("Could not find commit file {}", commitFile);
      return;
    }
    final String[] s = parent.getFileName().toString().split("_");
    if (s.length < 2) {
      LOGGER.error("could not find repo name for {}", parent.getFileName());
      return;
    }
    String repoUrl =
        String.format(
            "https://github.com/%s/%s.git",
            s[0], String.join("_", Arrays.asList(s).subList(1, s.length)));
    String commit = Files.lines(commitFile).findFirst().orElse("").trim();

    // run the build pipeline on the projects (for generating the call graph)
    final Path checkoutRepoFolder = Utils.checkOutRepo(repoUrl, commit);
    Path projectPom = checkoutRepoFolder.resolve("pom.xml");

    PipelineRunner pipelineRunner = new PipelineRunner(projectName, projectPom);
    // the project/module names and the associated maveninvokerproject
    final Map<String, MavenInvokerProject> run = pipelineRunner.run();

    // handle the modules
    List<UpdateSuggestion> aggResults = new ArrayList<>();

    for (Map.Entry<String, List<Result>> module : groupByModuleName.entrySet()) {
      try {

        aggResults.addAll(
            runOnModule(
                run.get(module.getKey()), csvFile, outputDir, module.getKey(), module.getValue()));
      } catch (IOException ex) {
        LOGGER.error("Failed on module: {}", module.getKey(), ex);
      }
    }

    try {
      Path outputCsvFile = outputDir.resolve(projectName + "_recommendation_results.csv");
      CSVWriter writer = new CSVWriter(new FileWriter(outputCsvFile.toFile()));
      StatefulBeanToCsv<UpdateSuggestion> sbc =
          new StatefulBeanToCsvBuilder<UpdateSuggestion>(writer)
              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
              .build();
      sbc.write(aggResults);
      writer.close();
      LOGGER.info("Wrote results to file: {}", outputCsvFile.getFileName().toString());

    } catch (CsvRequiredFieldEmptyException | CsvDataTypeMismatchException | IOException e) {
      LOGGER.error("Failed to write csv file with: ", e);
    }
  }

  public static List<UpdateSuggestion> runOnModule(
      MavenInvokerProject mavenInvokerProject,
      String csvFile,
      String outputDir,
      String moduleName,
      List<Result> results)
      throws IOException {
    return runOnModule(
        mavenInvokerProject, Paths.get(csvFile), Paths.get(outputDir), moduleName, results);
  }

  public static List<UpdateSuggestion> runOnModule(
      MavenInvokerProject mavenInvokerProject,
      Path csvFile,
      Path outputDir,
      String moduleName,
      List<Result> results)
      throws IOException {

    LOGGER.info("Running on project - module: {}", moduleName);

    ArrayList<Path> jsonGraphs = new ArrayList<>();
    try (Stream<Path> walkStream = Files.walk(csvFile.getParent())) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                // filename to search for
                String filename = moduleName;
                if (filename.contains("projectRun")) {
                  // remove it from the name
                  String[] split = moduleName.split("_");
                  final List<String> newName =
                      Arrays.stream(split)
                          .filter(x -> !StringUtils.startsWith(x, "projectRun"))
                          .collect(Collectors.toList());

                  filename = String.join("_", newName);
                }
                filename = filename + "_dependency-graph.json";
                if (StringUtils.equalsIgnoreCase(f.getFileName().toString(), filename)) {
                  // ignore graph analysis csv file
                  jsonGraphs.add(f);
                }
              });
    }

    if (jsonGraphs.isEmpty()) {
      LOGGER.error("Could not find graph file for module {}", moduleName);
      return Collections.emptyList();
    }

    // handle the module here

    final Path depGraphFile = jsonGraphs.get(0);

    // get the maven invoker project for the current module/scope
    if (mavenInvokerProject == null) {
      LOGGER.error("Could not find MavenInvokerProject for {}", moduleName);
      return Collections.emptyList();
    }

    final List<Result> filteredResults =
        results.stream()
            .filter(
                x -> StringUtils.isNotBlank(x.getOrgGav()) && StringUtils.isNotBlank(x.getNewGav()))
            .collect(Collectors.toList());

    LOGGER.debug("Filtered results size: {}", filteredResults.size());
    if (filteredResults.isEmpty()) {
      LOGGER.info("Filtered results empty, skipping");
      // skip this module, but not the whole project
      return Collections.emptyList();
    }
    RecommendationAlgorithm recommendationAlgorithm;
    try {
      recommendationAlgorithm = new RecommendationAlgorithm(mavenInvokerProject, depGraphFile);
    } catch (IOException ex) {
      LOGGER.error("Failed to establish neo4j connection");
      return Collections.emptyList();
    }
    List<UpdateSuggestion> aggResults = new ArrayList<>();
    for (Result result : filteredResults) {

      try {

        final List<UpdateSuggestion> updateSuggestion =
            recommendationAlgorithm.run(result.getOrgGav(), result.getNewGav());
        // set the project name for the update suggestion
        updateSuggestion.forEach(x -> x.setProjectName(moduleName));
        // add to the result set
        aggResults.addAll(updateSuggestion);

        LOGGER.info("Done with update suggestion found: {}", aggResults.size());

      } catch (IllegalArgumentException ex) {
        LOGGER.error("Failed ", ex);
        Path outputErrorFile = outputDir.resolve(moduleName + "_recommendation-error.txt");
        try (BufferedWriter writer =
            Files.newBufferedWriter(outputErrorFile, StandardCharsets.UTF_8)) {
          writer.write(ex.getMessage());
        } catch (IOException e) {
          e.printStackTrace();
        }
      } catch (MavenInvokerProject.BuildToolException e) {
        LOGGER.error("Failed to get Classpath for {}", moduleName, e);
      }
    }
    return aggResults;
  }

  private static Pair<Set<String>, Path> getDoneProjects(String statusFolder) {
    HashSet<String> doneProjects = new HashSet<>();
    Path statusCacheFolder = Paths.get(statusFolder);
    try {
      if (!Files.exists(statusCacheFolder)) {
        Files.createDirectory(statusCacheFolder);
      }
      {
        LOGGER.debug("Using status folder: " + statusCacheFolder.toAbsolutePath());
      }

      try (Stream<Path> paths = Files.walk(statusCacheFolder)) {
        paths
            .filter(Files::isRegularFile)
            .forEach(x -> doneProjects.add(x.getFileName().toString()));
      }
    } catch (IOException ex) {
      return Pair.of(Collections.emptySet(), statusCacheFolder);
    }
    return Pair.of(doneProjects, statusCacheFolder);
  }
}
