package de.upb.upcy.update;

import de.upb.upcy.update.graph.GraphManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainUnifedDepGrapGenerator {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(MainMavenComputeUpdateSuggestion.class);

  public static void main(String[] args) throws IOException {

    // create the command line parser
    CommandLineParser parser = new DefaultParser();

    // create the Options
    Options options = new Options();
    options.addOption(
        Option.builder("dg")
            .longOpt("dependency-graph")
            .desc(
                "the generated dependency graph as json: ```\n"
                    + "mvn com.github.ferstl:depgraph-maven-plugin:4.0.1:graph -DshowVersions -DshowGroupIds -DshowDuplicates -DshowConflicts -DgraphFormat=json\n"
                    + "``` ")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("appCp")
            .longOpt("appClasspath")
            .desc(
                "the maven classpath: ```\n"
                    + "echo \"$(pwd)/target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)\" > cp.txt\n"
                    + "``` ")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("out").longOpt("outputFile").desc("the output file").hasArg().required(false)
            .build());
    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);
      Path dependencyGraphjsonFile = Paths.get(line.getOptionValue("dg"));
      if (!Files.exists(dependencyGraphjsonFile)) {
        throw new IllegalArgumentException("please provide a dependency Graph json file");
      }
      Path appClasspathFile = Paths.get(line.getOptionValue("appClasspath"));

      if (!Files.exists(appClasspathFile)) {
        throw new IllegalArgumentException("please provide a file containing the classpath");
      }
      String outputFile = "unifiedDepTree.json";
      if(line.hasOption("out")){
        outputFile = line.getOptionValue("out");
      }

      String appClassPath = Files.readString(appClasspathFile).strip()
          .replaceAll("\\R+", "");
      GraphManager graphManager = new GraphManager(dependencyGraphjsonFile);
      graphManager.build(Arrays.asList(appClassPath.split(File.pathSeparator)));
      graphManager.exportUnifiedDepGraphToJson(outputFile);

    } catch (ParseException exp) {
      LOGGER.error("Unexpected command line argument:" + exp.getMessage());
      // automatically generate the help statement
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("help", options);
    }
  }
}
