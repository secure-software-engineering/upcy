package de.upb.upcy.update;

import com.opencsv.CSVWriter;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.update.build.PipelineRunner;
import de.upb.upcy.update.recommendation.RecommendationAlgorithm;
import de.upb.upcy.update.recommendation.UpdateSuggestion;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/**
 * Main class for computing updates for a Maven module. Input is read via the CLI.
 *
 * @author adann
 */
public class MainMavenComputeUpdateSuggestion {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(MainMavenComputeUpdateSuggestion.class);

  public static void main(String[] args) {

    // create the command line parser
    CommandLineParser parser = new DefaultParser();

    // create the Options
    Options options = new Options();
    options.addOption(
        Option.builder("module")
            .longOpt("maven-module")
            .desc("path to the maven module")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("dg")
            .longOpt("dependency-graph")
            .desc("the generated dependency graph as json")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("gav")
            .desc("the GAV of the dependency to update in the form - group:artifact:version")
            .hasArg()
            .required(true)
            .build());
    options.addOption(
        Option.builder("targetGav")
            .desc("the target GAV in the form - group:artifact:version")
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
      if (line.hasOption("preflight")) {
        System.out.println("Running Pre-Flight Checks...");
        // execute preflight check
        final boolean b = PreFlight.preFlightCheck();
        if (b) {
          System.out.println("Everything is working!");
          System.exit(0);
        }
        System.err.println("Pre-Flight Check failed!");
        System.exit(1);
      }

      handleModule(
          line.getOptionValue("module"),
          line.getOptionValue("dg"),
          line.getOptionValue("gav"),
          line.getOptionValue("targetGav"));
    } catch (ParseException exp) {
      LOGGER.error("Unexpected exception:" + exp.getMessage());
      // automatically generate the help statement
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("help", options);
    }
  }

  private static void handleModule(
      String moduleFolder, String depGraphFile, String orgGav, String targetGav) {
    Path modulePath = Paths.get(moduleFolder);
    Path outputDir = Paths.get(moduleFolder);

    final String projectName = modulePath.getFileName().toString();

    Path projectPom = modulePath.resolve("pom.xml");

    if (!Files.exists(projectPom)) {
      throw new IllegalArgumentException("No pom.xml found in: " + moduleFolder);
    }

    PipelineRunner pipelineRunner = new PipelineRunner(projectName, projectPom);

    // the project/module names and the associated maveninvokerproject
    final Map<String, MavenInvokerProject> run = pipelineRunner.run();

    // run on the modules
    List<UpdateSuggestion> updateSuggestion = Collections.emptyList();

    try {

      RecommendationAlgorithm recommendationAlgorithm =
          new RecommendationAlgorithm(run.get(projectName), Paths.get(depGraphFile));

      updateSuggestion = recommendationAlgorithm.run(orgGav, targetGav);

    } catch (IOException | MavenInvokerProject.BuildToolException ex) {
      LOGGER.error("Failed on module: {}", projectName, ex);
    }

    try {

      String fileNamePrefix = projectName;
      if (StringUtils.isEmpty(fileNamePrefix) || fileNamePrefix == ".") {
        fileNamePrefix = modulePath.getParent().getFileName().toString();
      }

      Path outputCsvFile = outputDir.resolve(fileNamePrefix + "_recommendation_results.csv");
      CSVWriter writer = new CSVWriter(new FileWriter(outputCsvFile.toFile()));
      StatefulBeanToCsv<UpdateSuggestion> sbc =
          new StatefulBeanToCsvBuilder<UpdateSuggestion>(writer)
              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
              .build();
      sbc.write(updateSuggestion);
      writer.close();
      LOGGER.info("Wrote results to file: {}", outputCsvFile.getFileName().toString());

    } catch (CsvRequiredFieldEmptyException | CsvDataTypeMismatchException | IOException e) {
      LOGGER.error("Failed to write csv file with: ", e);
    }
  }
}
