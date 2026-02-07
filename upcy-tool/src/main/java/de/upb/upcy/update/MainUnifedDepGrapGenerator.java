package de.upb.upcy.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainUnifedDepGrapGenerator {


  private static final Logger LOGGER =
      LoggerFactory.getLogger(MainMavenComputeUpdateSuggestion.class);

  public static void main(String[] args) {

    // create the command line parser
    CommandLineParser parser = new DefaultParser();

    // create the Options
    Options options = new Options();
    options.addOption(
        Option.builder("dg")
            .longOpt("dependency-graph")
            .desc("the generated dependency graph as json")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("preflight")
            .desc("check if everything is running")
            .optionalArg(true)
            .hasArg(false)
            .required(false)
            .build());

    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);


    } catch (ParseException exp) {
      LOGGER.error("Unexpected command line argument:" + exp.getMessage());
      // automatically generate the help statement
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("help", options);
    }
  }



  public static void run() {

    // TODO
    String moduleName;
    String projectPath;


    LOGGER.info("Running on project - module: {}", moduleName);

    ArrayList<Path> jsonGraphs = new ArrayList<>();
    try (Stream<Path> walkStream = Files.walk(Paths.get(projectPath))) {
      walkStream
          .filter(p -> p.toFile().isFile())
          .forEach(
              f -> {
                // filename to search for
                String filename = moduleName;
                if (filename.contains("projectRun")) {
                  // remove it from the name
                  String[] split = moduleName.split("_");
                  final List<String> newName =
                      Arrays.stream(split)
                          .filter(x -> !StringUtils.startsWith(x, "projectRun"))
                          .collect(Collectors.toList());

                  filename = String.join("_", newName);
                }
                filename = filename + "_dependency-graph.json";
                if (StringUtils.equalsIgnoreCase(f.getFileName().toString(), filename)) {
                  // ignore graph analysis csv file
                  jsonGraphs.add(f);
                }
              });
    }

    if (jsonGraphs.isEmpty()) {
      LOGGER.error("Could not find graph file for module {}", moduleName);
      return;
    }
    final Path depGraphFile = jsonGraphs.get(0);


  }


}
