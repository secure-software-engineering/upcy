package de.upb.upcy;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaProcess {
  private static final Logger LOGGER = LoggerFactory.getLogger(JavaProcess.class);

  private JavaProcess() {}

  public static int exec(Class klass, List<String> jvmArgs, List<String> args)
      throws IOException, InterruptedException {
    return exec(klass, jvmArgs, args, Long.MAX_VALUE);
  }

  public static int exec(
      Class klass, List<String> jvmArgs, List<String> args, long timeoutInSeconds)
      throws IOException, InterruptedException {
    String javaHome = System.getProperty("java.home");
    String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
    String classpath = System.getProperty("java.class.path");
    String className = klass.getName();

    List<String> command = new LinkedList<>();
    command.add(javaBin);
    if (jvmArgs != null) {
      command.addAll(jvmArgs);
    }
    command.add("-cp");
    command.add(classpath);
    command.add(className);
    if (args != null) {
      command.addAll(args);
    }
    LOGGER.info("Executing Process for class: {}", className);
    ProcessBuilder builder = new ProcessBuilder(command);
    Process process = builder.inheritIO().start();
    final boolean b = process.waitFor(timeoutInSeconds, TimeUnit.SECONDS);
    if (!b) {
      LOGGER.info("Timeout of process for class: {}", className);

      process.destroy();
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      LOGGER.debug("Wait for process kill for class: {}", className);
      process.waitFor();
    }
    return process.exitValue();
  }
}
