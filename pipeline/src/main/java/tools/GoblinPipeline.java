package tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import de.upb.upcy.base.mvn.IOUtils;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import de.upb.upcy.pipeline.NaiveUpdateStep;
import de.upb.upcy.pipeline.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoblinPipeline implements PipelineTool {

  private static Logger LOGGER = LoggerFactory.getLogger(GoblinPipeline.class);

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

    JsonNode dockerNetworkBy = Utils.getDockerNetworkBy("goblin-net");
    if (dockerNetworkBy == null) {
      throw new RuntimeException("Could not find docker network");
    }
    String dockerNetwork = dockerNetworkBy.get("Name").asText();

    String[] bashCmd =
        new String[] {
          "docker",
          "run",
          "-e",
          "LOGFILE=" + projectNameClear,
          "--rm",
          "--network=" + dockerNetwork,
          "-v",
          outputDir.toAbsolutePath().toString() + ":/var/log/",
          "-v",
          projectDir.toAbsolutePath().toString() + ":/app/",
          "ghcr.io/anddann/goblinupdater:1.0.0"
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
              "Process timed out when executing '%s'.\nStdout: %s\nStderr: %s",
              Joiner.on(" ").join(bashCmd), output, error),
          null);
    } else if (exitCode != 0) {
      throw new BuildToolException(
          String.format(
              "Process returned a non-zero exit code %d when executing '%s'.\nStdout: %s\nStderr: %s",
              exitCode, Joiner.on(" ").join(bashCmd), output, error),
          null);
    }
  }

  @Override
  public String getName() {
    return "GoblinUpdater";
  }
}
