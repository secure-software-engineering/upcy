package de.upb.upcy.pipeline;

import java.io.IOException;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.GoblinPipeline;
import tools.MvnPipeline.MavenPipelineTool;

public class Main {

  private static Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException {
    System.out.println(org.slf4j.impl.StaticLoggerBinder.getSingleton().getLoggerFactoryClassStr());
    System.out.println(org.apache.logging.log4j.LogManager.getFactory().getClass());
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    System.out.println(
        "Config file: " + ctx.getConfiguration().getConfigurationSource().getLocation());
    System.out.println("Root logger level: " + ctx.getConfiguration().getRootLogger().getLevel());

    if (args.length < 2) {
      LOGGER.error("No arguments given");
      return;
    }
    String rootDir = args[0];
    String workingDirectory = args[1];

//    ToolPipeline pipelineTool =
//        new ToolPipeline(Paths.get(rootDir), Paths.get(workingDirectory), new MavenPipelineTool());

    ToolPipeline pipelineTool =
        new ToolPipeline(Paths.get(rootDir), Paths.get(workingDirectory), new GoblinPipeline());

    pipelineTool.execute();
  }
}
