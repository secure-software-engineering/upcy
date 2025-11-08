package tools;

import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import de.upb.upcy.pipeline.NaiveUpdateStep;
import de.upb.upcy.pipeline.PomFileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MvnPipeline {

  public static class MavenPipelineTool implements PipelineTool {

    @Override
    public void runTool(
        Path projectDir,
        String projectName,
        Path csvFile,
        Path outputDir,
        Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule)
        throws Exception {

      MvnPipeline mvnPipeline = new MvnPipeline(projectName, projectDir.resolve("pom.xml"));
      List<InvokerProjectResult> run = mvnPipeline.run();
      for (InvokerProjectResult entry : run) {
        // write the log to a file
        String projectNameClear = entry.projectName.replaceAll(":", "_");
        Path logFile = outputDir.resolve(projectNameClear + ".log");
        Files.writeString(logFile, entry.invocationResult.getMiddle());
        Path errorLogFile = outputDir.resolve(projectNameClear + ".err");
        Files.writeString(errorLogFile, entry.invocationResult.getRight());
      }
    }

    @Override
    public String getName() {
      return "MavenBuildAndTest";
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(MvnPipeline.class);
  private final String projectName;
  private final Path projectPomFile;
  private final ExecutorService executorService;

  public MvnPipeline(String projectName, Path projectPomFile) {

    this.projectName = projectName;
    this.projectPomFile = projectPomFile;
    executorService = Executors.newFixedThreadPool(4);
  }

  public List<InvokerProjectResult> run() {
    if (!Files.exists(projectPomFile)) {
      LOGGER.error("Could not find pom file: {}", projectPomFile.toAbsolutePath());
    }

    LOGGER.info("Working on project: {}", projectPomFile);

    // run mvn compile install, to ease graph generation for aggregator projects
    // mvn clean compile install -DskipTests -Dmaven.test.skip=true
    MavenInvokerProject mavenInvokerProject = new MavenInvokerProject(projectPomFile);
    Triple<Integer, String, String> integerStringStringTriple = null;
    try {
      integerStringStringTriple =
          mavenInvokerProject.runCmd(
              "clean", "compile", "install", "-DskipTests", "-Dmaven.test.skip=true");

      if (integerStringStringTriple.getLeft() != 0) {
        // throw new MavenInvokerProject.BuildToolException(integerStringStringTriple.getRight());
        return Collections.singletonList(
            new InvokerProjectResult(projectName, mavenInvokerProject, integerStringStringTriple));
      }
      LOGGER.info("Successfully build initial with clean compile install");

    } catch (MavenInvokerProject.BuildToolException e) {
      LOGGER.error("Could not build pom file: {}", projectPomFile.toAbsolutePath());

      return Collections.singletonList(
          new InvokerProjectResult(
              projectName, mavenInvokerProject, Triple.of(-1, e.getStdout(), e.getStderr())));
    }

    // must be of type list, to allow proper writing in csv file, e.g., collection does not work
    Collection<Callable<InvokerProjectResult>> tasks = new ArrayList<>();
    Collection<Callable<InvokerProjectResult>> rootProjectCallable = new ArrayList<>();
    // check for multi-module aka aggregator maven projects
    try (Stream<Path> walkStream = Files.walk(projectPomFile.getParent())) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                if (StringUtils.equals(f.getFileName().toString(), "pom.xml")) {
                  LOGGER.info("Create Callable for {}", f.toAbsolutePath());

                  boolean isAlreadyInputPom;
                  try {
                    isAlreadyInputPom = Files.isSameFile(f, projectPomFile);
                  } catch (IOException e) {
                    isAlreadyInputPom = false;
                  }
                  if (isAlreadyInputPom) {
                    // the root project pom should not interfere with the parallelized builds
                    rootProjectCallable.add(() -> runOnSubmodule(f));
                  } else {
                    tasks.add(() -> runOnSubmodule(f));
                  }
                }
              });
    } catch (IOException exception) {
      LOGGER.error("Failed iterating dir ", exception);
    }
    List<InvokerProjectResult> aggResults = new ArrayList<>();

    List<Future<InvokerProjectResult>> futures;
    try {
      LOGGER.info("Found #{} projects to build", tasks.size());
      futures = executorService.invokeAll(tasks);
      for (Future<InvokerProjectResult> future : futures) {

        try {
          InvokerProjectResult x = future.get();
          aggResults.add(x);
        } catch (InterruptedException | ExecutionException e) {
          LOGGER.error("Failed task submission with: ", e);
        }
      }

      // now invoke the root project pom
      futures = executorService.invokeAll(rootProjectCallable);
      for (Future<InvokerProjectResult> future : futures) {
        try {
          InvokerProjectResult x = future.get();
          aggResults.add(x);
        } catch (InterruptedException | ExecutionException e) {
          LOGGER.error("Failed task submission with: ", e);
        }
      }

      executorService.shutdown();
    } catch (InterruptedException e) {
      LOGGER.error("Failed task submission with: ", e);
    }

    LOGGER.info("Done on project: {}", projectPomFile);
    return aggResults;
  }

  private InvokerProjectResult runOnSubmodule(Path f) throws IOException, BuildToolException {
    String newProjectName = projectName;
    if (!Files.isSameFile(projectPomFile, f)) {
      // we have a pom in a submodule
      MavenProject mavenProject = PomFileUtil.readPom(f);
      newProjectName =
          mavenProject.getArtifactId()
              + ":"
              + mavenProject.getGroupId()
              + ":"
              + mavenProject.getVersion();
    }
    LOGGER.info("Running on file: {}, with projectName: {}", f.toAbsolutePath(), newProjectName);

    MavenInvokerProject mavenInvokerProject = new MavenInvokerProject(f);
    Triple<Integer, String, String> compile = mavenInvokerProject.compile();

    return new InvokerProjectResult(newProjectName, mavenInvokerProject, compile);
  }

  private record InvokerProjectResult(
      String projectName,
      MavenInvokerProject mavenInvokerProject,
      Triple<Integer, String, String> invocationResult) {

  }
}
