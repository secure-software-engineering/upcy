package tools;

import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.base.mvn.MavenInvokerProject.BuildToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MvnPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(MvnPipeline.class);
  private final String projectName;
  private final Path projectPomFile;
  private final ExecutorService executorService;

  public MvnPipeline(String projectName, Path projectPomFile) {

    this.projectName = projectName;
    this.projectPomFile = projectPomFile;
    executorService = Executors.newFixedThreadPool(4);
  }

  public Map<String, MavenInvokerProject> run() {
    if (!Files.exists(projectPomFile)) {
      LOGGER.error("Could not find pom file: {}", projectPomFile.toAbsolutePath());
    }

    LOGGER.info("Working on project: {}", projectPomFile);

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

    // must be of type list, to allow proper writing in csv file, e.g., collection does not work
    Collection<Callable<org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject>>> tasks =
        new ArrayList<>();
    Collection<Callable<org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject>>>
        rootProjectCallable = new ArrayList<>();
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
    Map<String, MavenInvokerProject> aggResults = new HashMap<>();

    List<Future<org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject>>> futures;
    try {
      LOGGER.info("Found #{} projects to build", tasks.size());
      futures = executorService.invokeAll(tasks);
      for (Future<org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject>> future :
          futures) {

        try {
          org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject> x = future.get();
          aggResults.put(x.getLeft(), x.getRight());
        } catch (InterruptedException | ExecutionException e) {
          LOGGER.error("Failed task submission with: ", e);
        }
      }

      // now invoke the root project pom
      futures = executorService.invokeAll(rootProjectCallable);
      for (Future<org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject>> future :
          futures) {
        try {
          org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject> x = future.get();
          aggResults.put(x.getLeft(), x.getRight());
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

  private org.apache.commons.lang3.tuple.Pair<String, MavenInvokerProject> runOnSubmodule(Path f)
      throws IOException, BuildToolException {
    String newProjectName = projectName;
    if (!Files.isSameFile(projectPomFile, f)) {
      // we have a pom in a submodule
      newProjectName = projectName + "_" + f.getParent().getFileName().toString();
    }
    LOGGER.info("Running on file: {}, with projectName: {}", f.toAbsolutePath(), newProjectName);

    MavenInvokerProject mavenInvokerProject = new MavenInvokerProject(f);
    mavenInvokerProject.compile();

    return org.apache.commons.lang3.tuple.Pair.of(newProjectName, mavenInvokerProject);
  }
}
