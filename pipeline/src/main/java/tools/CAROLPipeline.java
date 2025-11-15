package tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import de.upb.upcy.base.mvn.IOUtils;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import de.upb.upcy.pipeline.NaiveUpdateStep;
import de.upb.upcy.pipeline.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CAROLPipeline implements PipelineTool {

  private static Logger LOGGER = LoggerFactory.getLogger(CAROLPipeline.class);

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

    // find the jar file
    String jarfilelocation = null;
    List<Path> jars;
    try (Stream<Path> s = Files.walk(outputDir.resolve("target"))) {
      jars = s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
          .collect(Collectors.toList());
    }

    if (jars.isEmpty()) {
      throw new IllegalArgumentException("Could not find jar file");
    }
    if (jars.size() > 1) {
      throw new IllegalArgumentException("Multiple jar files found");
    }

    jarfilelocation = jars.get(0).toAbsolutePath().toString();

    JsonNode dockerNetworkBy = Utils.getDockerNetworkBy("coral-net");
    if (dockerNetworkBy == null) {
      throw new RuntimeException("Could not find docker network");
    }
    String dockerNetwork = dockerNetworkBy.get("Name").asText();

    String[] bashCmd =
        new String[]{
            "docker", "run", "-e", "SEMSERVER_HOST=coral-semserver", "-e",
            "MONGO_HOST=coral-mongodb", "--rm",
            "--network=" + dockerNetwork, "-v",
            outputDir.toAbsolutePath().toString() + ":/usr/src/app/remediation_results/", "-v",
            projectDir.toAbsolutePath().toString() + ":/app/",
            "ghcr.io/anddann/goblinupdater:1.0.0", "/app/", "/app/" + jarfilelocation
        };

    LOGGER.info(
        "Running GoblinUpdater command '{}' on ",
        Joiner.on(" ").join(bashCmd),
        projectDir.toAbsolutePath().toString());

    ProcessBuilder processBuilder = new ProcessBuilder(bashCmd);

    final Triple<Integer, String, String> processRetCodeOutErr =
        IOUtils.awaitTermination(processBuilder.start(), -1);

    int exitCode = processRetCodeOutErr.getLeft();
    String output = processRetCodeOutErr.getMiddle();
    String error = processRetCodeOutErr.getRight();

    if (exitCode == IOUtils.TIMEOUT_EXITCODE) {
      throw new BuildToolException(
          String.format(
              "The Maven process timed out when executing '%s'.\nStdout: %s\nStderr: %s",
              Joiner.on(" ").join(bashCmd),
              output,
              error), null);
    } else if (exitCode != 0) {
      throw new BuildToolException(
          String.format(
              "Maven returned a non-zero exit code %d when executing '%s'.\nStdout: %s\nStderr: %s",
              exitCode, Joiner.on(" ").join(bashCmd),
              output,
              error), null);
    }


  }

  @Override
  public String getName() {
    return "GoblinUpdater";
  }
}
