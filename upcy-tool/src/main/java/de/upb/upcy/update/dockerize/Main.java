package de.upb.upcy.update.dockerize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import de.upb.upcy.base.build.Utils;
import de.upb.upcy.base.commons.RabbitMQCollective;
import de.upb.upcy.update.process.ComputeRecommendationProcess;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.lingala.zip4j.ZipFile;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main-Class for the Docker containers. Entrypoint to execute the recommendation pipeline in
 * distributed docker containers
 */
public class Main extends RabbitMQCollective {
  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final ArrayList<String> doneProjectNames = new ArrayList<>();
  private final ArrayList<String> todoProjectNames = new ArrayList<>();
  private IClient client;
  private Path projectDir;

  public Main() {
    super("THETIS_PROJECT_PIPELINE");
  }

  public static void main(String[] args) throws Exception {
    Main main = new Main();
    main.run();
  }

  @Override
  protected void doWorkerJob(Delivery delivery) throws IOException {
    Msg msg = OBJECT_MAPPER.readValue(delivery.getBody(), Msg.class);
    try {
      LOGGER.info("[Worker] Received Request");

      LOGGER.info("[Worker] Running Recommendation Pipeline for: {}", msg.getProjectNameFolder());
      // we have a folder projects/projects/...
      Path projectToCheckFolder =
          this.projectDir.resolve("projects").resolve(msg.getProjectNameFolder());

      List<Path> csvFiles = new ArrayList<>();
      try (Stream<Path> walkStream = Files.walk(projectToCheckFolder)) {
        walkStream
            .filter(p -> p.toFile().isFile())
            .forEach(
                f -> {
                  if (StringUtils.endsWith(f.getFileName().toString(), ".csv")
                      && !StringUtils.contains(f.getFileName().toString(), "_graph-analysis")
                      && !StringUtils.contains(
                          f.getFileName().toString(), "_options-analysis.csv")) {
                    // ignore graph analysis csv file
                    csvFiles.add(f);
                  }
                });
      }

      Path tmpDir = Files.createTempDirectory(msg.getProjectNameFolder());
      Path csvOutPutDir = Files.createDirectory(tmpDir.resolve(msg.getProjectNameFolder()));

      for (Path csvFile : csvFiles) {
        ComputeRecommendationProcess.handleProject(csvFile, csvOutPutDir);
        LOGGER.info("[Worker] Done with build pipeline");
      }
      LOGGER.debug("[Worker] Uploading results files");
      String timeStamp = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());

      final String zipFileName =
          msg.getProjectNameFolder() + "_recommendation" + "_" + timeStamp + ".zip";
      final ZipFile zipFile = new ZipFile(zipFileName);
      zipFile.addFolder(csvOutPutDir.toFile());

      client.uploadFile(zipFile.getFile());
      LOGGER.info("[Worker] Uploading files done");

    } catch (IOException | GitAPIException e) {
      LOGGER.error("[Worker] Failed with", e);
    }
  }

  @Override
  protected void doProducerJob(AMQP.BasicProperties props) throws Exception {

    final Set<Path> commitFiles = Utils.findCommitFiles(projectDir.toAbsolutePath().toString());
    int counter = 0;
    List<Path> commitFilesSorted = new ArrayList<>(commitFiles);
    Collections.sort(
        commitFilesSorted, Comparator.comparing(o -> o.getParent().getFileName().toString()));
    for (Path commitFile : commitFilesSorted) {

      Msg msg = new Msg();
      final String projectNameFolder = commitFile.getParent().getFileName().toString();
      msg.setProjectNameFolder(projectNameFolder);

      if (!todoProjectNames.isEmpty()) {
        // always takes preference
        if (!todoProjectNames.contains(projectNameFolder)) {
          LOGGER.info("Not in todo list {} ", msg.getProjectNameFolder());
          continue;
        }
      } else if (doneProjectNames.contains(msg.getProjectNameFolder())) {
        LOGGER.info("Project {} is in done list.", msg.getProjectNameFolder());
        continue;
      }

      LOGGER.info("[Producer] Enqueue: {}", msg);
      String jsonString = OBJECT_MAPPER.writeValueAsString(msg);
      this.enqueue(props, jsonString.getBytes());
      counter++;
    }
    LOGGER.info("[Producer] Done with #{}", counter);
  }

  @Override
  protected void preFlightCheck() throws IOException {
    this.client =
        IClient.createClient(
            System.getenv("FILESERVER_HOST"),
            System.getenv("FILESERVER_USER"),
            System.getenv("FILESERVER_PASS"));

    // download projects file
    try {
      final Path target = Paths.get("done_projects.txt");
      client.downloadFile("done_projects.txt", target);

      try (BufferedReader br = Files.newBufferedReader(target)) {
        String line;
        while ((line = br.readLine()) != null) {
          doneProjectNames.add(line.trim());
        }
      }
    } catch (IOException exception) {
      LOGGER.error("Failed to download project file", exception);
    }
    LOGGER.info("Found #{} done projects", doneProjectNames.size());

    try {
      final Path target = Paths.get("todo_projects.txt");
      client.downloadFile("todo_projects.txt", target);

      if (Files.size(target) != 0) {

        try (BufferedReader br = Files.newBufferedReader(target)) {
          String line;
          while ((line = br.readLine()) != null) {
            todoProjectNames.add(line.trim());
          }
        }
      }
    } catch (IOException exception) {
      LOGGER.error("Failed to download todo list file", exception);
    }
    LOGGER.info("Found #{} todo projects", todoProjectNames.size());

    final Path target = Paths.get("project_input_recommendation.zip");
    client.downloadFile("project_input_recommendation.zip", target);

    final ZipFile zipFile = new ZipFile(target.toFile());
    final Path projectDir = Paths.get("projects");
    zipFile.extractAll(projectDir.toAbsolutePath().toString());
    this.projectDir = projectDir;
  }

  @Override
  protected void shutdown() {
    if (this.isWorkerNode() && this.client != null) {

      this.client.close();
    }
    // nothing

    LOGGER.info("Shutdown");
  }
}
