package tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import de.upb.upcy.base.mvn.IOUtils;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import de.upb.upcy.base.mvn.MavenInvokerProject.JDK_MVN_DOCKER_IMAGE;
import de.upb.upcy.pipeline.NaiveUpdateStep;
import de.upb.upcy.pipeline.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoralPipeline implements PipelineTool {

  private static Logger LOGGER = LoggerFactory.getLogger(CoralPipeline.class);

  @Override
  public void runTool(
      Path projectDir,
      String projectName,
      Path csvFile,
      Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule)
      throws Exception {

    String projectNameClear = projectName.replaceAll(":", "_");

    // invoke goblin update -- in container
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }

    // compile project
    MavenInvokerProject.USE_BASH = false;
    MavenInvokerProject mavenInvokerProject =
        new MavenInvokerProject(projectDir.resolve("pom.xml"), JDK_MVN_DOCKER_IMAGE.CORRETTO8);
    mavenInvokerProject.packageMvn();

    // find the jar file
    List<Path> jars;
    try (Stream<Path> s = Files.walk(projectDir)) {
      jars =
          s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
              .collect(Collectors.toList());
    }

    if (jars.isEmpty()) {
      throw new IllegalArgumentException("Could not find jar file");
    }
    String jarfilelocation = null;
    if (jars.size() > 1) {
      List<Path> filteredJars =
          jars.stream()
              .filter(
                  p ->
                      !p.getFileName().toString().endsWith("-sources.jar")
                          && !p.getFileName().toString().endsWith("-javadoc.jar"))
              .collect(Collectors.toList());
      String[] projectGav = projectNameClear.split("_");
      filteredJars =
          filteredJars.stream()
              .filter(x -> x.getFileName().toString().contains(projectGav[1]))
              .collect(Collectors.toList());

      if (filteredJars.size() > 1) {
        // select the one with dependencies
        filteredJars.stream()
            .sorted(Comparator.comparing(p -> p.toFile().length(), Comparator.reverseOrder()));
        LOGGER.warn("Found more than one jar with artifact name, choose largest file");
      } else if (filteredJars.isEmpty()) {
        throw new IllegalArgumentException("Could not find jar file");
      }

      // select the bigger file?
      jarfilelocation = projectDir.relativize(filteredJars.get(0)).toString();
    }

    JsonNode dockerNetworkBy = Utils.getDockerNetworkBy("coral-net");
    if (dockerNetworkBy == null) {
      throw new RuntimeException("Could not find docker network");
    }
    String dockerNetwork = dockerNetworkBy.get("Name").asText();

    String[] bashCmd =
        new String[]{
            "docker",
            "run",
            "-e",
            "SEMSERVER_HOST=coral-semserver",
            "-e",
            "MONGO_HOST=coral-mongodb",
            "-e",
            "WEAVER_HOST=goblin-weaver",
            "-e",
            "M2_REPO=/root/.m2/repository/",
            "--rm",
            "--network=" + dockerNetwork,
            "-v",
            outputDir.toAbsolutePath().toString() + ":/usr/src/app/remediation_results/",
            "-v",
            projectDir.toAbsolutePath().toString() + ":/app/",
            "ghcr.io/anddann/coral:0.9",
            "/app/",
            "/app/" + jarfilelocation
        };

    LOGGER.info(
        "Running Coral command '{}' on ",
        Joiner.on(" ").join(bashCmd));

    ProcessBuilder processBuilder = new ProcessBuilder(bashCmd);

    final Triple<Integer, String, String> processRetCodeOutErr =
        IOUtils.awaitTermination(processBuilder.start(), -1);

    int exitCode = processRetCodeOutErr.getLeft();
    String output = processRetCodeOutErr.getMiddle();
    String error = processRetCodeOutErr.getRight();

    if (exitCode == IOUtils.TIMEOUT_EXITCODE) {
      throw new BuildToolException(
          String.format("The process timed out when executing '%s'", String.join(" ", bashCmd)),
          output,
          error);
    } else if (exitCode != 0) {
      throw new BuildToolException(
          String.format(
              "The returned a non-zero exit code %d when executing '%s'.",
              exitCode, String.join(" ", bashCmd)),
          output,
          error);
    }
  }

  @Override
  public String getName() {
    return "Coral";
  }
}
