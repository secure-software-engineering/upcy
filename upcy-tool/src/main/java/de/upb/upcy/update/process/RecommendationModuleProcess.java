package de.upb.upcy.update.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import de.upb.upcy.base.mvn.MavenInvokerProject;
import de.upb.upcy.update.MainComputeUpdateSuggestion;
import de.upb.upcy.update.build.Result;
import de.upb.upcy.update.recommendation.UpdateSuggestion;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecommendationModuleProcess {
  private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationModuleProcess.class);

  public static void main(String[] args) {
    String jsonString = args[0];

    ObjectMapper objectMapper = new ObjectMapper();

    try {
      final InputParameter inputParameter =
          objectMapper.readValue(jsonString, InputParameter.class);

      final List<UpdateSuggestion> updateSuggestions =
          MainComputeUpdateSuggestion.runOnModule(
              inputParameter.getMavenInvokerProject(),
              inputParameter.getCsvFile(),
              inputParameter.getOutputDir(),
              inputParameter.getModuleName(),
              inputParameter.getResults());
      // write modules in separate files --- since we cannot serialize them into one (soot method
      // is not serializable with json)
      Path outputCsvFile =
          Paths.get(inputParameter.getOutputDir())
              .resolve(inputParameter.getModuleName() + "_recommendation_results.csv");
      CSVWriter writer = new CSVWriter(new FileWriter(outputCsvFile.toFile()));
      StatefulBeanToCsv<UpdateSuggestion> sbc =
          new StatefulBeanToCsvBuilder<UpdateSuggestion>(writer)
              .withSeparator(CSVWriter.DEFAULT_SEPARATOR)
              .build();
      sbc.write(updateSuggestions);
      writer.close();
      LOGGER.info("Wrote results to file: {}", outputCsvFile.getFileName().toString());

    } catch (CsvRequiredFieldEmptyException | CsvDataTypeMismatchException | IOException e) {
      LOGGER.error("Failed to write csv file with: ", e);
    }
  }

  @Data
  static class InputParameter {
    private MavenInvokerProject mavenInvokerProject;
    private String csvFile;
    private String outputDir;
    private String moduleName;
    private List<Result> results;
    private String resultFile;
  }
}
