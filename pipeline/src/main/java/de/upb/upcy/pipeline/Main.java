package de.upb.upcy.pipeline;

import java.io.IOException;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.CAROLPipeline;
import tools.GoblinPipeline;
import tools.MvnPipeline.MavenPipelineTool;
import tools.PipelineTool;

public class Main {

  private static Logger LOGGER = LoggerFactory.getLogger(Main.class);


  public static PipelineTool chooseTool(String toolname) {
    switch (toolname.toLowerCase()) {
      case "maven":
        return new MavenPipelineTool();
      case "mvn":
        return new MavenPipelineTool();
      case "goblin":
        return new GoblinPipeline();
      case "carol":
        return new CAROLPipeline();
      default:
        throw new IllegalArgumentException("Unknown toolname: " + toolname);
    }
  }

  public static void main(String[] args) throws IOException {
    System.out.println(org.slf4j.impl.StaticLoggerBinder.getSingleton().getLoggerFactoryClassStr());
    System.out.println(org.apache.logging.log4j.LogManager.getFactory().getClass());
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    System.out.println(
        "Config file: " + ctx.getConfiguration().getConfigurationSource().getLocation());
    System.out.println("Root logger level: " + ctx.getConfiguration().getRootLogger().getLevel());

    if (args.length < 4) {
      LOGGER.error("No arguments given");
      return;
    }
    String rootDir = args[0];
    String workingDirectory = args[1];
    String toolname = args[2];

    ToolPipeline pipelineTool =
        new ToolPipeline(Paths.get(rootDir), Paths.get(workingDirectory), chooseTool(toolname));

    pipelineTool.execute();
  }
}
