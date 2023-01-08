package de.upb.upcy.update.build;

import de.upb.upcy.base.mvn.MavenInvokerProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pipeline {
  private static final Logger LOGGER = LoggerFactory.getLogger(Pipeline.class);
  private final Path rootPomFile;
  private final MavenInvokerProject mavenInvokerProject;
  private final String projectName;
  private final Path modulePomFile;

  public Pipeline(Path rootProjectPomFile, Path modulePomFile, String projectName) {
    this.rootPomFile = rootProjectPomFile;
    this.modulePomFile = modulePomFile;
    if (!Files.exists(rootPomFile)) {
      throw new IllegalArgumentException("Could not find pom file: " + rootProjectPomFile);
    }
    this.mavenInvokerProject = new MavenInvokerProject(modulePomFile);
    this.projectName = projectName;
  }

  public MavenInvokerProject getMavenInvokerProject() {
    return mavenInvokerProject;
  }

  public void runPipeline() {

    // compile the code
    this.compileAndTest();
  }

  public void compileAndTest() {
    // 1. test if the normal build goal succeeds
    // 2. test if the normal test goal succeeds
    LOGGER.info("Building project: {}", projectName);
    try {
      Triple<Integer, String, String> out = mavenInvokerProject.compile();
      if (out.getLeft() == 0) {
        // nothing to do
        LOGGER.trace("Built project without error");
      } else {
        LOGGER.error("Built project failed");
      }
    } catch (MavenInvokerProject.BuildToolException e) {
      LOGGER.error("Built project failed", e);
      return;
    }
  }

  private Path getSurefireReportsDirectory() {
    return modulePomFile.getParent().resolve("surefire-reports");
  }
}
