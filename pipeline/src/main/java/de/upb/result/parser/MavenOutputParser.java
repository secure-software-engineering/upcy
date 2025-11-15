package de.upb.result.parser;

import com.opencsv.bean.CsvBindByName;
import com.opencsv.bean.CsvRecurse;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenOutputParser {

  public static class TestResult {

    private int testsRun;
    @CsvBindByName(column = "failures")
    private int failures;
    @CsvBindByName(column = "errors")
    private int errors;
    @CsvBindByName(column = "skipped")
    private int skipped;
    @CsvBindByName(column = "timeElapsed")
    private String timeElapsed;
    private List<String> failedTests = new ArrayList<>();

    public int getTestsRun() {
      return testsRun;
    }

    public void setTestsRun(int testsRun) {
      this.testsRun = testsRun;
    }

    public int getFailures() {
      return failures;
    }

    public void setFailures(int failures) {
      this.failures = failures;
    }

    public int getErrors() {
      return errors;
    }

    public void setErrors(int errors) {
      this.errors = errors;
    }

    public int getSkipped() {
      return skipped;
    }

    public void setSkipped(int skipped) {
      this.skipped = skipped;
    }

    public String getTimeElapsed() {
      return timeElapsed;
    }

    public void setTimeElapsed(String timeElapsed) {
      this.timeElapsed = timeElapsed;
    }

    public List<String> getFailedTests() {
      return failedTests;
    }

    public boolean hasTests() {
      return testsRun > 0;
    }

    public boolean allTestsPassed() {
      return testsRun > 0 && failures == 0 && errors == 0;
    }

    @Override
    public String toString() {
      if (!hasTests()) {
        return "No tests executed";
      }

      StringBuilder sb = new StringBuilder();
      sb.append("Tests Run: ").append(testsRun).append("\n");
      sb.append("Failures: ").append(failures).append("\n");
      sb.append("Errors: ").append(errors).append("\n");
      sb.append("Skipped: ").append(skipped).append("\n");
      sb.append("Time Elapsed: ").append(timeElapsed != null ? timeElapsed : "N/A").append("\n");
      sb.append("Status: ").append(allTestsPassed() ? "ALL PASSED" : "SOME FAILED").append("\n");
      return sb.toString();
    }
  }

  public static class BuildResult {

    @CsvBindByName(column = "buildSuccess")
    private boolean success;

    @CsvBindByName(column = "projectName")
    private String projectName;

    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    @CsvBindByName(column = "buildTime")
    private String buildTime;
    private List<String> compiledFiles = new ArrayList<>();
    @CsvRecurse
    private TestResult testResult = new TestResult();


    public boolean isSuccess() {
      return success;
    }

    public void setSuccess(boolean success) {
      this.success = success;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    public String getBuildTime() {
      return buildTime;
    }

    public void setBuildTime(String buildTime) {
      this.buildTime = buildTime;
    }

    public List<String> getCompiledFiles() {
      return compiledFiles;
    }

    public TestResult getTestResult() {
      return testResult;
    }


    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("Build Status: ").append(success ? "SUCCESS" : "FAILURE").append("\n");
      sb.append("Build Time: ").append(buildTime != null ? buildTime : "N/A").append("\n");
      sb.append("Compiled Files: ").append(compiledFiles.size()).append("\n");
      sb.append("Errors: ").append(errors.size()).append("\n");
      sb.append("Warnings: ").append(warnings.size()).append("\n");
      if (testResult.hasTests()) {
        sb.append("\nTest Results:\n");
        sb.append(testResult.toString());
      }
      return sb.toString();
    }

    public String getProjectName() {
      return projectName;
    }

    public void setProjectName(String projectName) {
      this.projectName = projectName;
    }
  }

  public static BuildResult parseMavenOutput(String output) {
    BuildResult result = new BuildResult();
    String[] lines = output.split("\n");

    // Patterns for parsing
    Pattern errorPattern = Pattern.compile("\\[ERROR\\](.*)");
    Pattern warningPattern = Pattern.compile("\\[WARNING\\](.*)");
    Pattern successPattern = Pattern.compile("BUILD SUCCESS");
    Pattern failurePattern = Pattern.compile("BUILD FAILURE");
    Pattern timePattern = Pattern.compile("Total time:\\s+(.*)");
    Pattern compilingPattern = Pattern.compile("Compiling \\d+ source files? to (.*)");

    // Test patterns
    Pattern testSummaryPattern = Pattern.compile(
        "Tests run:\\s+(\\d+),\\s+Failures:\\s+(\\d+),\\s+Errors:\\s+(\\d+),\\s+Skipped:\\s+(\\d+)");
    Pattern testTimePattern = Pattern.compile(
        "Tests run:\\s+\\d+.*Time elapsed:\\s+([\\d.]+\\s*s?)");
    Pattern failedTestPattern = Pattern.compile(
        "(.*?)\\s+Time elapsed:\\s+[\\d.]+.*<<<\\s+(FAILURE|ERROR)");
    Pattern testResultsPattern = Pattern.compile("Results\\s*:");

    boolean inTestResults = false;

    for (String line : lines) {
      // Check for build status
      if (successPattern.matcher(line).find()) {
        result.setSuccess(true);
      } else if (failurePattern.matcher(line).find()) {
        result.setSuccess(false);
      }

      // Extract errors
      Matcher errorMatcher = errorPattern.matcher(line);
      if (errorMatcher.find()) {
        result.getErrors().add(errorMatcher.group(1).trim());
      }

      // Extract warnings
      Matcher warningMatcher = warningPattern.matcher(line);
      if (warningMatcher.find()) {
        result.getWarnings().add(warningMatcher.group(1).trim());
      }

      // Extract build time
      Matcher timeMatcher = timePattern.matcher(line);
      if (timeMatcher.find()) {
        result.setBuildTime(timeMatcher.group(1).trim());
      }

      // Extract compilation info
      Matcher compilingMatcher = compilingPattern.matcher(line);
      if (compilingMatcher.find()) {
        result.getCompiledFiles().add(compilingMatcher.group(1).trim());
      }

      // Check if entering test results section
      if (testResultsPattern.matcher(line).find()) {
        inTestResults = true;
      }

      // Extract test summary (usually appears in "Results:" section)
      Matcher testSummaryMatcher = testSummaryPattern.matcher(line);
      if (testSummaryMatcher.find() && inTestResults) {
        result.getTestResult().setTestsRun(Integer.parseInt(testSummaryMatcher.group(1)));
        result.getTestResult().setFailures(Integer.parseInt(testSummaryMatcher.group(2)));
        result.getTestResult().setErrors(Integer.parseInt(testSummaryMatcher.group(3)));
        result.getTestResult().setSkipped(Integer.parseInt(testSummaryMatcher.group(4)));
      }

      // Extract test time
      Matcher testTimeMatcher = testTimePattern.matcher(line);
      if (testTimeMatcher.find()) {
        result.getTestResult().setTimeElapsed(testTimeMatcher.group(1).trim());
      }

      // Extract failed test names
      Matcher failedTestMatcher = failedTestPattern.matcher(line);
      if (failedTestMatcher.find()) {
        String testName = failedTestMatcher.group(1).trim();
        String failureType = failedTestMatcher.group(2);
        result.getTestResult().getFailedTests().add(testName + " [" + failureType + "]");
      }
    }

    return result;
  }


  public static void writeResultsToCsv(String filePath, List<BuildResult> persons)
      throws IOException, CsvRequiredFieldEmptyException, CsvDataTypeMismatchException {

    try (FileWriter writer = new FileWriter(filePath)) {
      StatefulBeanToCsv<BuildResult> beanToCsv = new StatefulBeanToCsvBuilder<BuildResult>(writer)
          .withApplyQuotesToAll(false) // optional
          .build();

      beanToCsv.write(persons);
    }
  }

  public static void main(String[] args) {
    try {

      String projectPath = ".";

      // Parse command line arguments
      if (args.length > 0) {
        projectPath = args[0];
      } else {
        throw new IllegalArgumentException("Missing project path");
      }
      Path resultsFolder = Paths.get(projectPath).resolve("MavenBuildAndTest");
      Set<Path> logfiles = new HashSet<>();
      // find the files
      try (Stream<Path> stream = Files.list(resultsFolder)) {
        logfiles =
            stream.filter(file -> Files.isRegularFile(file) && file.endsWith(".log"))
                .collect(Collectors.toSet());
      }

      for (Path logfile : logfiles) {
        BuildResult buildResult = parseMavenOutput(Files.readString(logfile));
        buildResult.setProjectName(logfile.getFileName().toString().replace("/", ":"));
      }


    } catch (Exception e) {
      System.err.println("Error executing Maven command: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}