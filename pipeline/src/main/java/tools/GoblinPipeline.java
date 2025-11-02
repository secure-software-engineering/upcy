package tools;

import client.ClientLPGA;
import de.upb.upcy.pipeline.ToolPipeline;
import de.upb.upcy.update.build.NaiveUpdateStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class GoblinPipeline extends ToolPipeline {

  @Override
  public void runTool(Path projectDir, String projectName, Path csvFile, Path outputDir,
      Map<String, List<NaiveUpdateStep>> naiveUpdatesStepsPerModule) {

    // invoke goblin update

    Properties props = System.getProperties();
    props.setProperty("gate.home", "http://gate.ac.uk/wiki/code-repository");

    Properties props = System.getProperties();
    props.setProperty("gate.home", "http://gate.ac.uk/wiki/code-repository");
    ClientLPGA.main(null);

  }
}
