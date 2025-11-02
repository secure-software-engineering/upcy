package tools;

import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.pipeline.ToolPipeline;
import de.upb.upcy.update.build.NaiveUpdateStep;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MvnCompileTest extends ToolPipeline {

  public static final Logger LOGGER = LogManager.getLogger(MvnCompileTest.class);

  @Override
  public void runTool(Path projectDir, String projectName, Path csvFile, Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule) {
    // run mvn compile install, to ease graph generation for aggregator projects
    // mvn clean compile install -DskipTests -Dmaven.test.skip=true
    MavenInvokerProject mavenInvokerProject = new MavenInvokerProject(projectPomFile);

    try {
      Triple<Integer, String, String> integerStringStringTriple =
          mavenInvokerProject.runCmd(
              "clean", "compile", "install", "-DskipTests", "-Dmaven.test.skip=true");

      if (integerStringStringTriple.getLeft() != 0) {
        throw new MavenInvokerProject.BuildToolException(integerStringStringTriple.getRight());
      }
      LOGGER.info("Successfully build initial with clean compile install");

    } catch (MavenInvokerProject.BuildToolException e) {
      LOGGER.error("Could not build pom file: {}", projectPomFile.toAbsolutePath());

      String msg =
          "Failed project compile and install : " + projectName + " with " + e.getMessage();

      LOGGER.error(
          "Error building project {} : {}", projectName, msg.getBytes(StandardCharsets.UTF_8));
      return Collections.emptyMap();
    }
  }


}
