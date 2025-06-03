package tools;

import de.upb.upcy.pipeline.AbstractToolRun;
import de.upb.upcy.update.build.NaiveUpdateStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class GoblinRun extends AbstractToolRun {

  @Override
  public void runTool(Path projectDir, String projectName, Path csvFile, Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule) {

    // invoke goblin update
  }
}
