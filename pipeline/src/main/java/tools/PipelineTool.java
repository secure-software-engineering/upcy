package tools;

import de.upb.upcy.pipeline.NaiveUpdateStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface PipelineTool {

  void runTool(
      Path projectDir,
      String projectName,
      Path csvFile,
      Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule)
      throws Exception;

  String getName();
}
