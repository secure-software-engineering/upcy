package de.upb.upcy.update.process;

import static java.util.stream.Collectors.groupingBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import de.upb.upcy.base.build.Utils;
import de.upb.upcy.base.commons.JavaProcess;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.update.build.PipelineRunner;
import de.upb.upcy.update.build.Result;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComputeRecommendationProcess {

  private static final Logger LOGGER = LoggerFactory.getLogger(ComputeRecommendationProcess.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // Timeout 45min
  private static long TIMEOUT_IN_SEC = 2700L;

  private static boolean RUN_IN_PROCESS = true;

  static {
    String timeOut = System.getenv("TIMEOUT");
    if (StringUtils.isNotBlank(timeOut)) {
      try {
        TIMEOUT_IN_SEC = Long.parseLong(timeOut);
      } catch (NumberFormatException e) {
        // nothing
      }
    }
    String runInProcess = System.getenv("RUN_IN_PROCESS");
    if (StringUtils.isNotBlank(runInProcess)) {
      try {
        RUN_IN_PROCESS = Boolean.parseBoolean(runInProcess);
      } catch (NumberFormatException e) {
        // nothing
      }
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

    for (Map.Entry<String, List<Result>> module : groupByModuleName.entrySet()) {

      try {
        final Path resFile = Files.createTempFile("resFile", "json");
        // invoke the process on the children modules
        RecommendationModuleProcess.InputParameter inputParameter =
            new RecommendationModuleProcess.InputParameter();
        inputParameter.setCsvFile(csvFile.toAbsolutePath().toString());
        inputParameter.setResults(module.getValue());
        inputParameter.setOutputDir(outputDir.toAbsolutePath().toString());
        inputParameter.setModuleName(module.getKey());
        inputParameter.setMavenInvokerProject(run.get(module.getKey()));
        inputParameter.setResultFile(resFile.toAbsolutePath().toString());

        // run the process and maybe kill it
        String json = OBJECT_MAPPER.writeValueAsString(inputParameter);
        if (RUN_IN_PROCESS) {
          System.out.println(
              "Start RecommendationModuleProcess in OWN process for: " + module.getKey());
          final int retVal =
              JavaProcess.exec(
                  RecommendationModuleProcess.class,
                  Collections.emptyList(),
                  Collections.singletonList(json),
                  TIMEOUT_IN_SEC);
          System.out.println("Done RecommendationModuleProcess with return value: " + retVal);
        } else {
          System.out.println(
              "Start RecommendationModuleProcess in SAME process for: " + module.getKey());
          String[] args = new String[1];
          args[0] = json;
          RecommendationModuleProcess.main(args);
        }
      } catch (InterruptedException e) {
        LOGGER.error("Timout for module");
      } catch (JsonProcessingException e) {
        LOGGER.error("Jackson exception for module", e);
      } catch (IOException exception) {
        LOGGER.error("IO exception for module", exception);
      }
    }
  }
}
