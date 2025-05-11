package de.upb.upcy.pipeline;

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
import de.upb.upcy.update.recommendation.UpdateSuggestion;
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
public class GeneralRun {

  private static final Logger LOGGER = LoggerFactory.getLogger(GeneralRun.class);


  public static void main(String[] args) throws IOException {

    if (args.length == 0) {
      LOGGER.error("No arguments given");
      return;
    }
    String rootDir = args[0];

    // get the csv file, containing the update steps, to work on
    List<Path> updateStepCSVForProjects = new ArrayList<>();
    try (Stream<Path> walkStream = Files.walk(Paths.get(rootDir))) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                //FIXME: more specific
                if (StringUtils.endsWith(f.getFileName().toString(), "_update-step.csv")) {
                  // ignore graph analysis csv file
                  updateStepCSVForProjects.add(f);
                }
              });
    }

    final Pair<Set<String>, Path> done = initDoneProjects("./recommendation_status");
    Set<String> doneProjects = done.getLeft();
    Path statusCacheFolder = done.getRight();

    // run
    for (Path csvFile : updateStepCSVForProjects) {

      Path parentDir = csvFile.getParent();
      Path grandFatherDir = parentDir.getParent();

      String repoName = parentDir.getFileName().toString();
      String ownerName = grandFatherDir.getFileName().toString();

      Path commitFile = parentDir.resolve("COMMIT");
      if (!Files.exists(commitFile)) {
        LOGGER.error("Could not find commit file {}", commitFile);
        return;
      }

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

    // checkout project and commit from benchmark

    // prepare for tool, e.g., choose dependency to update

    // run tool

    // run in docker container with java installed

    // reset project
    // git reset --hard
    // git clean -df

    // run next tool

  }

  private static Pair<Set<String>, Path> initDoneProjects(String statusFolder) {
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


  private static void checkOutProject(Path targetDir, String repo, String commitId) {

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


}
