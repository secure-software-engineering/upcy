package de.upb.upcy.pipeline;

import java.io.IOException;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.MvnCompileTest;
import tools.PipelineTool;

public class Main {

  private static Logger LOGGER = LoggerFactory.getLogger(Main.class);


  public static void main(String[] args) {

    if (args.length < 2) {
      LOGGER.error("No arguments given");
      return;
    }
    String rootDir = args[0];
    String workingDirectory = args[1];

    PipelineTool pipelineTool = new PipelineTool();

    ToolPipeline abstractToolRun = new ToolPipeline(Paths.get(rootDir),
        Paths.get(workingDirectory), pipelineTool);

    abstractToolRun.execute();


  }
}
