// package tools;
//
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.opencsv.CSVWriter;
// import com.opencsv.bean.StatefulBeanToCsv;
// import com.opencsv.bean.StatefulBeanToCsvBuilder;
// import com.opencsv.exceptions.CsvDataTypeMismatchException;
// import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
// import de.upb.upcy.base.mvn.MavenInvokerProject;
// import de.upb.upcy.update.MainComputeUpdateSuggestion;
// import de.upb.upcy.update.build.NaiveUpdateStep;
// import de.upb.upcy.update.build.PipelineRunner;
// import de.upb.upcy.update.recommendation.UpdateSuggestion;
// import java.io.FileWriter;
// import java.io.IOException;
// import java.nio.file.Path;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.Map;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
//
// public class UpcyPipeline implements PipelineTool {
//
//  private static final Logger LOGGER = LoggerFactory.getLogger(UpcyPipeline.class);
//
//  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
//
//  @Override
//  public void runTool(
//      Path projectDir,
//      String projectName,
//      Path csvFile,
//      Path outputDir,
//      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule)
//      throws Exception {
//    Path projectPom = projectDir.resolve("pom.xml");
//
//    PipelineRunner pipelineRunner = new PipelineRunner(projectName, projectPom);
//    // the project/module names and the associated maven invokerproject
//    final Map<String, MavenInvokerProject> run = pipelineRunner.run();
//
//    // handle the modules
//    List<UpdateSuggestion> aggResults = new ArrayList<>();
//
//    // mapping to existing class
//
//    for (Map.Entry<String, List<NaiveUpdateStep>> module : naiveUpdatesStepsPerModule.entrySet())
// {
//      try {
//
//        aggResults.addAll(
//            MainComputeUpdateSuggestion.runOnModule(
//                run.get(module.getKey()), csvFile, outputDir, module.getKey(),
// module.getValue()));
//      } catch (IOException ex) {
//        LOGGER.error("Failed on module: {}", module.getKey(), ex);
//      }
//    }
//
//    try {
//      Path outputCsvFile = outputDir.resolve(projectName + "_recommendation_results.csv");
//      CSVWriter writer = new CSVWriter(new FileWriter(outputCsvFile.toFile()));
//      StatefulBeanToCsv<UpdateSuggestion> sbc =
//          new StatefulBeanToCsvBuilder<UpdateSuggestion>(writer)
//              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
//              .build();
//      sbc.write(aggResults);
//      writer.close();
//      LOGGER.info("Wrote results to file: {}", outputCsvFile.getFileName().toString());
//
//    } catch (CsvRequiredFieldEmptyException | CsvDataTypeMismatchException | IOException e) {
//      LOGGER.error("Failed to write csv file with: ", e);
//    }
//  }
//
//  @Override
//  public String getName() {
//    return "";
//  }
// }
