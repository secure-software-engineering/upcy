package de.upb.upcy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author adann
 */
public class MainManualTrigger {
  private static final Logger LOGGER = LoggerFactory.getLogger(MainManualTrigger.class);

  public static void main(String[] args) throws IOException {

    if (args.length == 0) {
      LOGGER.error("No arguments given");
      return;
    }
    List<String> result = Files.readAllLines(Paths.get(args[0]));

    final List<String> collect = result.stream().sorted().collect(Collectors.toList());
    for (int i = collect.size() - 1; i > 0; i--) {
      String appendName = "/Users/adann/sciebo/phd/thetis-experiements/2022-12-25-results/";
      final String projectName = collect.get(i);
      LOGGER.info("Working on project: {}", projectName);
      String[] updateArgs = new String[1];
      updateArgs[0] = appendName + projectName;
      if (Files.exists(Paths.get(updateArgs[0]))) {
        if (updateArgs[0].contains("zhoutaoo_SpringCloud")) {
          continue;
        }
        MainComputeUpdateSuggestion.main(updateArgs);
        LOGGER.info("Done with {}", projectName);
        String contentToAppend = projectName + "\n";
        Files.write(
            Paths.get(appendName + "manuel_trigger.txt"),
            contentToAppend.getBytes(),
            StandardOpenOption.APPEND,
            StandardOpenOption.CREATE);
      } else {
        LOGGER.error("No folder {}", updateArgs[0]);
      }
    }
  }
}
