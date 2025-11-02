package de.upb.upcy.pipeline;

import static java.util.stream.Collectors.groupingBy;

import com.opencsv.CSVWriter;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import de.upb.upcy.base.build.Utils;
import de.upb.upcy.update.build.NaiveUpdateStep;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PipelineTool;


/**
 * Main class for running the evaluation experiments. Requires as an input the folder containing the
 * projects and _update-step.csv files.
 *
 * @author adann
 */
public class ToolPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolPipeline.class);
  private final Path benchmark_updatesteps_folder;
  private final Path resultsDir;
  private final PipelineTool tool;


  public ToolPipeline(Path benchmark_updatesteps_folder, Path resultsDir, PipelineTool tool) {
    this.benchmark_updatesteps_folder = benchmark_updatesteps_folder;
    this.resultsDir = resultsDir;
    this.tool = tool;
  }


  private void execute() throws IOException {
    List<Path> updateStepCSVForProjects = this.getUpdateStepCSVFiles(benchmark_updatesteps_folder);

    String toolName = tool.getName();
    Path toolsOutputFolder = resultsDir.resolve(toolName);
    Set<Path> benchmarkProjects = new HashSet<>();
    try (Stream<Path> stream = Files.list(this.resultsDir)) {
      benchmarkProjects = stream
          .filter(file -> Files.isDirectory(file))
          .collect(Collectors.toSet());
    }
    // run
    for (Path benchmarkProject : benchmarkProjects) {

      Path tool_project_outputfolder = toolsOutputFolder.resolve(benchmarkProject.getFileName());
      tool_project_outputfolder.toFile().mkdirs();

      Path doneIndicatorFile = tool_project_outputfolder.resolve("DONE");
      if (Files.exists(doneIndicatorFile)) {
        LOGGER.info("Project {} already completed", tool_project_outputfolder);
        continue;
      }

      Path commitFile = benchmarkProject.resolve("COMMIT");
      if (!Files.exists(commitFile)) {
        LOGGER.error("Could not find commit file {}", commitFile);
        continue;
      }
      Path repoFile = benchmarkProject.resolve("REPO");
      if (!Files.exists(repoFile)) {
        LOGGER.error("Could not find repo file {}", repoFile);
        continue;
      }
      String commit = Files.lines(commitFile).findFirst().orElse("").trim();

      Path csvFile = Files.list(benchmarkProject)
          .filter(x -> StringUtils.endsWith(x.getFileName().toString(), "_update-steps.csv"))
          .findFirst().orElse(null);
      if (csvFile == null) {
        LOGGER.error("Could not find UpdateStep file in {}", benchmarkProject);
      }

      String repoUrl = Files.readString(repoFile, StandardCharsets.UTF_8);

      try {
        // checkout project and commit from benchmark

        Path checkedoutProject = checkoutProject(repoUrl, commit);
        runToolOnProject(checkedoutProject, csvFile, parentDir);

        Files.createFile(doneIndicatorFile);
        Files.write(doneIndicatorFile, "DONE".getBytes(StandardCharsets.UTF_8));

        // reset repo
        Utils.resetRepo(checkedoutProject);

      } catch (IOException e) {
        LOGGER.error("Failed to handle file: " + parentDir.getFileName(), e);
      } catch (GitAPIException e) {
        LOGGER.error("Failed to Checkout project {} with", parentDir.getFileName(), e);
      }
    }

    // run in docker container with java installed

    // reset project
    // git reset --hard
    // git clean -df

    // run next tool
  }

  private static @NotNull List<Path> getUpdateStepCSVFiles(Path benchmarkRootDir)
      throws IOException {
    // get the csv file, containing the update steps, to work on
    List<Path> updateStepCSVForProjects = new ArrayList<>();
    try (Stream<Path> walkStream = Files.walk(benchmarkRootDir)) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                //FIXME: more specific
                if (StringUtils.endsWith(f.getFileName().toString(), "_update-steps.csv")) {
                  // ignore graph analysis csv file
                  updateStepCSVForProjects.add(f);
                }
              });
    }
    return updateStepCSVForProjects;
  }

  private Pair<Set<String>, Path> initDoneProjects(String statusFolder) {
    HashSet<String> doneProjects = new HashSet<>();
    Path statusCacheFolder = Paths.get(statusFolder);
    try {
      if (!Files.exists(statusCacheFolder)) {
        Files.createDirectory(statusCacheFolder);
      }
      LOGGER.debug("Using status folder: " + statusCacheFolder.toAbsolutePath());
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


  private Path checkoutProject(String owner, String repoName, String commitId)
      throws GitAPIException, IOException {
    String repoUrl =
        String.format(
            "https://github.com/%s/%s.git", owner, repoName);
    return checkoutProject(repoUrl, commitId);
  }

  private Path checkoutProject(String repoUrl, String commitId)
      throws GitAPIException, IOException {
    return Utils.checkOutRepo(repoUrl, commitId);
  }

  public void runToolOnProject(Path projectDir, Path csvFile, Path outputDir)
      throws Exception {
    final Path parent = csvFile.getParent();

    final String projectName = parent.getFileName().toString();

    List<NaiveUpdateStep> results;
    try (Reader reader = Files.newBufferedReader(csvFile)) {
      CsvToBean<NaiveUpdateStep> sbc =
          new CsvToBeanBuilder<NaiveUpdateStep>(reader)
              .withType(NaiveUpdateStep.class)
              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
              .build();
      results = sbc.parse();
    }
    LOGGER.info("Successfully parsed csv file: {}", csvFile.getFileName().toString());

    final Map<String, List<NaiveUpdateStep>> naiveUpdateStepsPerModule =
        results.stream().collect(groupingBy(NaiveUpdateStep::getProjectName));

    LOGGER.info("Running on project: {}", projectName);
    // prepare for tool, e.g., choose dependency to update

    // run tool
    this.tool.runTool(projectDir, projectName, csvFile, outputDir, naiveUpdateStepsPerModule);

  }


}
