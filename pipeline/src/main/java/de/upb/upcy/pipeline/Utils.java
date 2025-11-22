package de.upb.upcy.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {

  private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

  public static final ObjectMapper MAPPER = new ObjectMapper();

  public static Pair<String, String> getRepoAndCommit(Path commitFile) throws IOException {
    String repoUrl =
        String.format(
            "https://github.com/%s/%s.git",
            commitFile.getParent().getParent().getFileName(), commitFile.getParent().getFileName());
    // read the commitString from the file;
    String line = Files.lines(commitFile).findFirst().get().trim();
    return Pair.of(repoUrl, line);
  }

  public static Path checkOutRepo(String repoUrl, String commit)
      throws IOException, GitAPIException {

    final Path tmpDir = Files.createTempDirectory("projectRun");

    LOGGER.info("Cloning " + repoUrl + " into " + tmpDir);
    final Git git = Git.cloneRepository().setURI(repoUrl).setDirectory(tmpDir.toFile()).call();

    LOGGER.info("Check out commit: {}", commit);

    git.checkout().setName(commit).setForce(true).call();
    LOGGER.info("Completed Cloning");

    return tmpDir;
  }

  public static void resetRepo(Path repositoryDir) throws IOException, GitAPIException {
    Git gitRepo = Git.open(repositoryDir.toFile());
    gitRepo.reset().setMode(ResetCommand.ResetType.HARD).call();
    gitRepo.clean().setCleanDirectories(true).setForce(true).call();
  }

  public static Set<Path> findCommitFiles(String projectsRootFolder) throws IOException {
    Set<Path> foundCommitFiles = new HashSet<>();
    try (Stream<Path> walkStream = Files.walk(Paths.get(projectsRootFolder))) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                if (f.getFileName().toString().equals("COMMIT")) {
                  foundCommitFiles.add(f);
                }
              });
    }

    return foundCommitFiles;
  }

  public static JsonNode getDockerNetworkBy(String name)
      throws IOException, ExecutionException, InterruptedException {
    // docker network ls --format json --filter name=goblin-net
    String out;

    String[] bashCmd =
        new String[] {"docker", "network", "ls", "--format", "json", "--filter", "name=" + name};
    ProcessBuilder processBuilder = new ProcessBuilder(bashCmd);

    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();

    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

    String line;
    String output = "";
    while ((line = reader.readLine()) != null) {
      output += line;
    }

    int exit = process.waitFor();

    int exitCode = exit;
    String error = null;
    if (exitCode == 0) {
      JsonNode jsonNode = MAPPER.readTree(output);
      return jsonNode;
    }
    throw new IOException("Could not find network, error: " + error);
  }
}
