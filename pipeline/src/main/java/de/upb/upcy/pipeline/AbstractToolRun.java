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
public abstract class AbstractToolRun {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractToolRun.class);


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
                if (StringUtils.endsWith(f.getFileName().toString(), "_update-steps.csv")) {
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
      String commit = Files.lines(commitFile).findFirst().orElse("").trim();

      try {
        // checkout project and commit from benchmark

        Path projectDir = checkoutProject(ownerName, repoName, commit);
        runToolOnProject(projectDir, csvFile, parentDir);

        Path statusFile = statusCacheFolder.resolve(csvFile.getFileName().toString() + ".status");

        Files.createFile(statusFile);
        Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));

        // reset repo
        Utils.resetRepo(projectDir);

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

  private static Pair<Set<String>, Path> initDoneProjects(String statusFolder) {
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


  private static Path checkoutProject(String owner, String repoName, String commitId)
      throws GitAPIException, IOException {
    String repoUrl =
        String.format(
            "https://github.com/%s/%s.git", owner, repoName);
    return Utils.checkOutRepo(repoUrl, commitId);
  }

  public static void runToolOnProject(Path projectDir, Path csvFile, Path outputDir)
      throws IOException, GitAPIException {
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
    this.runTool(projectDir, projectName, csvFile, outputDir, naiveUpdateStepsPerModule);

  }


  public abstract void runTool(Path projectDir, String projectName, Path csvFile, Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule);


}
