package de.upb.upcy;

import static java.util.stream.Collectors.groupingBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.upb.upcy.base.commons.ArtifactInfo;
import de.upb.upcy.base.mvn.MavenSearchAPIClient;
import de.upb.upcy.base.sigtest.MainGetStatistics;
import de.upb.upcy.base.sigtest.db.MongoDBHandler;
import de.upb.upcy.base.sigtest.db.model.generate.SigTestDBDoc;
import de.upb.upcy.eval.compability.SigABICompareProcess;
import de.upb.upcy.eval.compability.SigSrcCompareProcess;
import de.upb.upcy.eval.compability.SigTestProcess;
import de.upb.upcy.eval.compability.SootDiffCompareProcess;
import de.upb.upcy.update.Gav;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIXME -- the status file only uses the orgGav name, and thus, does not work for the compare
// Methods (SRC, ABI, SootDIFF)

public class MainComputeIncompatibilities {
  private static final Logger LOGGER = LoggerFactory.getLogger(MainComputeIncompatibilities.class);

  /** timout 3 minutes */
  private static long TIMEOUT_IN_SEC = 360L;

  public static int N_THREADS = 4;

  static {
    String nthreads = System.getenv("N_THREADS");
    if (StringUtils.isNotBlank(nthreads)) {
      try {
        N_THREADS = Integer.parseInt(nthreads);
      } catch (NumberFormatException e) {
        // nothing
      }
    }

    String timeOut = System.getenv("TIMEOUT");
    if (StringUtils.isNotBlank(timeOut)) {
      try {
        TIMEOUT_IN_SEC = Long.parseLong(timeOut);
      } catch (NumberFormatException e) {
        // nothing
      }
    }
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final Path inputFile;
  private final Map<Gav, List<Gav>> orgNewGavs;

  public MainComputeIncompatibilities(Path inputFile) throws IOException {
    this.inputFile = inputFile;
    orgNewGavs = MainComputeIncompatibilities.readInFile(this.inputFile);
  }

  public static Map<Gav, List<Gav>> readInFile(Path inputFile) throws IOException {
    Map<Gav, List<Gav>> orgNewGavs = new HashMap<>();
    final List<String> lines = Files.readAllLines(inputFile);
    int counter = 0;
    for (String line : lines) {
      // skip head
      if (counter++ == 0) {
        continue;
      }

      String[] split = line.split(",");
      if (split.length != 6) {
        continue;
      }

      Gav org = new Gav();
      org.group = split[0].trim();
      org.artifact = split[1].trim();
      org.version = split[2].trim();

      Gav newG = new Gav();
      newG.group = split[3].trim();
      newG.artifact = split[4].trim();
      newG.version = split[5].trim();

      // store in map
      orgNewGavs.computeIfAbsent(org, k -> new ArrayList<>()).add(newG);
    }
    return orgNewGavs;
  }

  public static void main(String[] args) throws IOException {

    Options options = new Options();

    Option inputFile =
        Option.builder("f")
            .longOpt("file")
            .hasArg(true)
            .desc("input file containing the GAVs")
            .required(true)
            .build();
    options.addOption(inputFile);

    Option config =
        Option.builder("c")
            .longOpt("config")
            .argName("config")
            .hasArg()
            .required(true)
            .desc("Set config option")
            .build();
    options.addOption(config);

    // define parser
    CommandLine cmd;
    CommandLineParser parser = new DefaultParser();
    HelpFormatter helper = new HelpFormatter();

    try {
      Path filePath = null;
      cmd = parser.parse(options, args);
      if (cmd.hasOption("f")) {
        String fileName = cmd.getOptionValue("f");
        System.out.println("Input file is: " + fileName);
        filePath = Paths.get(fileName);
      }

      if (filePath == null || !Files.exists(filePath)) {
        System.err.println("Input file does not exist");
        System.exit(-1);
      }
      MainComputeIncompatibilities thetisMain = new MainComputeIncompatibilities(filePath);
      if (cmd.hasOption("c")) {
        String opt_config = cmd.getOptionValue("config");
        System.out.println("Config set to " + opt_config);

        switch (opt_config) {
          case "genSig":
            System.out.println("Generate Signatures");
            thetisMain.generateSignatures();
            break;
          case "cmpSrc":
            System.out.println("Compare Source");
            thetisMain.compareSource();
            break;
          case "cmpABI":
            System.out.println("Compare ABI");
            thetisMain.compareABI();
            break;
          case "cmpSootDiff":
            System.out.println("Compare SootDiff");
            thetisMain.compareSootDiff();
            break;
          case "genStats":
            System.out.println("Generate Stats");
            thetisMain.genStats();
            break;
          default:
            System.err.println("Valid options are: genSig, cmpSrc, cmpABI, cmpSootDiff, genStats");
            break;
        }
      }

    } catch (ParseException e) {
      System.out.println(e.getMessage());
      helper.printHelp("Usage:", options);
      System.exit(0);
    }
  }

  public void genStats() throws IOException {

    final Pair<Set<String>, Path> res = getDoneProjects("./gen_stats_status");
    Path statusCacheFolder = res.getRight();
    Set<String> doneProjects = res.getLeft();

    // run in separate process
    ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    for (Gav orgGav : orgNewGavs.keySet()) {

      executorService.submit(
          () -> {
            try {
              Path statusFile = statusCacheFolder.resolve(orgGav.toString());

              MainGetStatistics mainGetStatistics = new MainGetStatistics();

              mainGetStatistics.handle(
                  orgGav.group,
                  orgGav.artifact,
                  sigTestDBDocs ->
                      MainComputeIncompatibilities.pairsForComparison(sigTestDBDocs, orgNewGavs));

              Files.createFile(statusFile);

              Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));

            } catch (IOException e) {
              LOGGER.error("Failed StatsGen of: " + orgGav, e);
            }
          });
    }
    awaitTerminationAfterShutdown(executorService);
  }

  public static Map<Pair<String, String>, Collection<Pair<SigTestDBDoc, SigTestDBDoc>>>
      pairsForComparison(Collection<SigTestDBDoc> sigTestDBDocs, Map<Gav, List<Gav>> orgNewGavs) {

    Map<Pair<String, String>, Collection<Pair<SigTestDBDoc, SigTestDBDoc>>> pairsToComparePerGA =
        new HashMap<>();

    // filter out android versions (for guava), since we don't want to compare them, and they
    // disturb
    // the base wise comparison
    Collection<SigTestDBDoc> filteredSigTestDocs =
        sigTestDBDocs.stream()
            .filter(x -> !StringUtils.contains(x.getArtifactInfo().getVersion(), "android"))
            .collect(Collectors.toList());

    // group by GAV
    Map<Pair<String, String>, List<SigTestDBDoc>> groupByGa =
        filteredSigTestDocs.stream()
            .collect(
                groupingBy(
                    post ->
                        new ImmutablePair<>(
                            post.getArtifactInfo().getGroupId(),
                            post.getArtifactInfo().getArtifactId())));

    for (Map.Entry<Pair<String, String>, List<SigTestDBDoc>> entry : groupByGa.entrySet()) {

      // get the org from our file
      for (SigTestDBDoc sigTestDBDoc : entry.getValue()) {
        Gav toCheck = new Gav();
        toCheck.group = sigTestDBDoc.getArtifactInfo().getGroupId();
        toCheck.artifact = sigTestDBDoc.getArtifactInfo().getArtifactId();
        toCheck.version = sigTestDBDoc.getArtifactInfo().getVersion();

        if (orgNewGavs.containsKey(toCheck)) {
          // get the list
          final List<Gav> gavs = orgNewGavs.get(toCheck);

          // find the sigtest doc and create pairs
          for (Gav gavToCompare : gavs) {
            for (SigTestDBDoc sigTestDBDocInner : entry.getValue()) {

              if (StringUtils.equals(
                  gavToCompare.version, sigTestDBDocInner.getArtifactInfo().getVersion())) {
                final ImmutablePair<SigTestDBDoc, SigTestDBDoc>
                    sigTestDBDocSigTestDBDocImmutablePair =
                        new ImmutablePair<>(sigTestDBDoc, sigTestDBDocInner);
                final Collection<Pair<SigTestDBDoc, SigTestDBDoc>> pairs =
                    pairsToComparePerGA.computeIfAbsent(entry.getKey(), k -> new HashSet<>());
                pairs.add(sigTestDBDocSigTestDBDocImmutablePair);
              }
            }
          }
        }
      }
    }

    return pairsToComparePerGA;
  }

  private static ArtifactInfo createArtifactInfo(Gav gav) {
    ArtifactInfo newArtifact = new ArtifactInfo();
    newArtifact.setRepoURL(MavenSearchAPIClient.MAVEN_CENTRAL);
    newArtifact.setGroupId(gav.group);
    newArtifact.setArtifactId(gav.artifact);
    newArtifact.setVersion(gav.version);
    newArtifact.setP("jar");
    return newArtifact;
  }

  public void compareSootDiff() throws IOException {

    final Pair<Set<String>, Path> res = getDoneProjects("./sootdiff_cmp_status");
    Path statusCacheFolder = res.getRight();
    Set<String> doneProjects = res.getLeft();

    // run in separate process
    ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);
    for (Gav orgGav : orgNewGavs.keySet()) {
      executorService.submit(
          () -> {
            Path statusFile = statusCacheFolder.resolve(orgGav.toString());
            try {
              String json = OBJECT_MAPPER.writeValueAsString(orgGav);
              System.out.println(
                  "Start de.upb.thetis.eval.compatibility.SootDiffCompareProcess for: " + orgGav);
              ArrayList<String> parameters = new ArrayList<>();
              parameters.add(this.inputFile.toAbsolutePath().toString());
              parameters.add(json);
              final int retVal =
                  JavaProcess.exec(
                      SootDiffCompareProcess.class,
                      Collections.emptyList(),
                      parameters,
                      TIMEOUT_IN_SEC);
              System.out.println(
                  "Done de.upb.thetis.eval.compatibility.SootDiffCompareProcess with return value: "
                      + retVal);
              Files.createFile(statusFile);

              Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
              System.err.println("Timout for artifact: " + orgGav);
            } catch (IOException e) {
              e.printStackTrace();
            }
          });
    }
    awaitTerminationAfterShutdown(executorService);
  }

  public void compareABI() throws IOException {

    final Pair<Set<String>, Path> res = getDoneProjects("./sigtest_cmp_abi_status");
    Path statusCacheFolder = res.getRight();
    Set<String> doneProjects = res.getLeft();

    // run in separate process
    ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    for (Gav orgGav : orgNewGavs.keySet()) {

      executorService.submit(
          () -> {
            Path statusFile = statusCacheFolder.resolve(orgGav.toString());
            try {
              String json = OBJECT_MAPPER.writeValueAsString(orgGav);
              System.out.println(
                  "Start de.upb.thetis.eval.compability.SigABICompareProcess for: " + orgGav);
              ArrayList<String> parameters = new ArrayList<>();
              parameters.add(this.inputFile.toAbsolutePath().toString());
              parameters.add(json);
              final int retVal =
                  JavaProcess.exec(
                      SigABICompareProcess.class,
                      Collections.emptyList(),
                      parameters,
                      TIMEOUT_IN_SEC);
              System.out.println(
                  "Done de.upb.thetis.eval.compatibility.SigABICompareProcess with return value: "
                      + retVal);
              Files.createFile(statusFile);

              Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
              System.err.println("Timout for artifact: " + orgGav);
            } catch (IOException e) {
              e.printStackTrace();
            }
          });
    }
    awaitTerminationAfterShutdown(executorService);
  }

  public void compareSource() throws IOException {

    // debug here

    MongoDBHandler mongoDBHandler = MongoDBHandler.getInstance();
    final Iterable<SigTestDBDoc> by = mongoDBHandler.findBy("commons-codec", "commons-codec");
    List<SigTestDBDoc> result = new ArrayList<>();
    by.forEach(result::add);
    MainComputeIncompatibilities.pairsForComparison(result, this.orgNewGavs);

    final Pair<Set<String>, Path> res = getDoneProjects("./sigtest_cmp_src_status");
    Path statusCacheFolder = res.getRight();
    Set<String> doneProjects = res.getLeft();
    // run in separate process
    ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    for (Gav orgGav : orgNewGavs.keySet()) {

      executorService.submit(
          () -> {
            Path statusFile = statusCacheFolder.resolve(orgGav.toString());

            try {
              String json = OBJECT_MAPPER.writeValueAsString(orgGav);
              System.out.println(
                  "Start de.upb.thetis.eval.compatibility.SigSrcCompareProcess for: " + orgGav);
              ArrayList<String> parameters = new ArrayList<>();
              parameters.add(this.inputFile.toAbsolutePath().toString());
              parameters.add(json);
              final int retVal =
                  JavaProcess.exec(
                      SigSrcCompareProcess.class,
                      Collections.emptyList(),
                      parameters,
                      TIMEOUT_IN_SEC);
              System.out.println(
                  "Done de.upb.thetis.eval.compatibility.SigSrcCompareProcess with return value: "
                      + retVal);
              Files.createFile(statusFile);

              Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
              LOGGER.error("Timout for artifact: " + orgGav);

            } catch (JsonProcessingException e) {
              LOGGER.error("Fail", e);

            } catch (IOException exception) {
              LOGGER.error("Fail", exception);
              try {
                Files.write(statusFile, exception.getMessage().getBytes(StandardCharsets.UTF_8));
              } catch (IOException ex) {
                // nothing
              }
            }
          });
    }
    awaitTerminationAfterShutdown(executorService);
  }

  private Pair<Set<String>, Path> getDoneProjects(String statusFolder) {
    HashSet<String> doneProjects = new HashSet<>();
    Path statusCacheFolder = Paths.get(statusFolder);
    try {
      if (!Files.exists(statusCacheFolder)) {
        Files.createDirectory(statusCacheFolder);
      }
      {
        LOGGER.debug("Using status folder: " + statusCacheFolder.toAbsolutePath());
      }

      try (Stream<Path> paths = Files.walk(statusCacheFolder)) {
        paths
            .filter(Files::isRegularFile)
            .forEach(x -> doneProjects.add(x.getFileName().toString()));
      }
    } catch (IOException ex) {
      return Pair.of(Collections.emptySet(), statusCacheFolder);
    }
    return Pair.of(doneProjects, statusCacheFolder);
  }

  public void generateSignatures() throws IOException {

    final Pair<Set<String>, Path> res = getDoneProjects("./sigtest_gen_status");
    Path statusCacheFolder = res.getRight();
    Set<String> doneProjects = res.getLeft();

    final List<ArtifactInfo> artifactInfos = generateArtifactsForSig(this.orgNewGavs);

    // run in separate process
    ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    for (ArtifactInfo el : artifactInfos) {

      if (doneProjects.contains(el.toString())) {
        LOGGER.info("Skipping File: {}, already in status folder", el);
        continue;
      }
      executorService.submit(
          () -> {
            Path statusFile = statusCacheFolder.resolve(el.toString());

            try {

              String json = OBJECT_MAPPER.writeValueAsString(el);
              System.out.println(
                  "Start de.upb.thetis.eval.compatibility.SigTestProcess for: " + el);
              final int retVal =
                  JavaProcess.exec(
                      SigTestProcess.class,
                      Collections.emptyList(),
                      Collections.singletonList(json),
                      TIMEOUT_IN_SEC);
              System.out.println(
                  "Done de.upb.thetis.eval.compatibility.SigTestProcess with return value: "
                      + retVal);
              Files.createFile(statusFile);

              Files.write(statusFile, "DONE".getBytes(StandardCharsets.UTF_8));

            } catch (InterruptedException e) {
              System.err.println("Timout for artifact: " + el);

            } catch (IOException e) {
              LOGGER.error("Failed", e);
            }
          });
    }
    awaitTerminationAfterShutdown(executorService);
  }

  public void awaitTerminationAfterShutdown(ExecutorService threadPool) {
    threadPool.shutdown();
    try {
      if (!threadPool.awaitTermination(60, TimeUnit.DAYS)) {
        threadPool.shutdownNow();
      }
    } catch (InterruptedException ex) {
      threadPool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public List<ArtifactInfo> generateArtifactsForSig(Map<Gav, List<Gav>> orgNewGavs) {
    HashSet<ArtifactInfo> setOfGavs = new HashSet<>();

    for (Map.Entry<Gav, List<Gav>> gavListEntry : orgNewGavs.entrySet()) {

      setOfGavs.add(createArtifactInfo(gavListEntry.getKey()));
      gavListEntry.getValue().forEach(x -> setOfGavs.add(createArtifactInfo(x)));
    }

    return new ArrayList<>(setOfGavs);
  }
}
