package tools;

import client.ClientLPGA;
import com.google.common.base.Joiner;
import de.upb.upcy.base.mvn.IOUtils;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import de.upb.upcy.pipeline.Main;
import de.upb.upcy.pipeline.NaiveUpdateStep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

    // invoke goblin update -- in container
    String[] bashCmd =
        new String[]{
            "docker", "run", ",-e", "LOGFILE=", "--rm", "--network=goblin-neo4j", "-v",
            outputDir.resolve(projectName).toAbsolutePath().toString() + ":/var/log/", "-v",
            projectDir.toAbsolutePath().toString() + ":/app/", "ghcr.io/anddann/goblinupdater:1.0.0"
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
              Joiner.on(" ").join(bashCmd)),
          output,
          error);
    } else if (exitCode != 0) {
      throw new BuildToolException(
          String.format(
              "Maven returned a non-zero exit code %d when executing '%s'.\nStdout: %s\nStderr: %s",
              exitCode, Joiner.on(" ").join(bashCmd)),
          output,
          error);
    }


  }

  @Override
  public String getName() {
    return "GoblinUpdater";
  }
}
