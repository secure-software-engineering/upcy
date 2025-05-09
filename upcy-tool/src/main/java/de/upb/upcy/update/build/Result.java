package de.upb.upcy.update.build;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.opencsv.bean.CsvBindByName;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result {
  @JsonProperty @CsvBindByName private String projectName;
  @JsonProperty @CsvBindByName private int inDegree = 0;
  @JsonProperty @CsvBindByName private String orgGav;
  @JsonProperty @CsvBindByName private String newGav;
  @JsonProperty @CsvBindByName private boolean transitive = false;
  @JsonProperty @CsvBindByName private OUTCOME buildResult;
  @JsonProperty @CsvBindByName private OUTCOME testResult;
  @JsonProperty @CsvBindByName private String buildError;
  @JsonProperty @CsvBindByName private String testErrors;
  @JsonProperty @CsvBindByName private int nrtestErrors = 0;
  @JsonProperty @CsvBindByName private String testFailures;
  @JsonProperty @CsvBindByName private int nrtestFailures = 0;
  @JsonProperty @CsvBindByName private int nrOfNewerVersions = 0;

  @JsonIgnore
  public void setTestErrors(List<String> testErrors) {
    this.testErrors = String.join(";", testErrors);
    this.setNrtestErrors(testErrors.size());
  }

  @JsonIgnore
  public void setTestFailures(List<String> testFailures) {
    this.testFailures = String.join(";", testFailures);

    this.setNrtestFailures(testFailures.size());
  }

  public enum OUTCOME {
    SKIP,
    NO_UPDATES,
    FAIL,
    SUCCESS
  }
}
