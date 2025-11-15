package de.upb.result.parser;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MvnParser {

  private final Path mvnDir;

  // erstelle ein globales file


  public MvnParser(String benchmarkResultDir) {
    mvnDir = Paths.get(benchmarkResultDir).resolve("MavenBuildAndTest");


  }

  public void start(){


  }


  public void parseMvnOutput(String mvn) {

  }

}
