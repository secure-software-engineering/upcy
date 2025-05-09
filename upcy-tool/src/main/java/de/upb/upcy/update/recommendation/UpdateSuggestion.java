package de.upb.upcy.update.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.opencsv.bean.CsvBindAndSplitByName;
import com.opencsv.bean.CsvBindByName;
import de.upb.upcy.update.recommendation.check.Violation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

/** @author adann */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateSuggestion {

  @CsvBindByName private String projectName;
  @CsvBindByName private String orgGav;
  @CsvBindByName private String targetGav;
  @CsvBindByName private String updateGav;
  @CsvBindByName private Collection<Violation> violations;

  @CsvBindByName
  @CsvBindAndSplitByName(elementType = Pair.class, writeDelimiter = "/")
  private List<Pair<String, String>> updateSteps = new ArrayList<>();

  @CsvBindByName private boolean isSimpleUpdate;
  @CsvBindByName private boolean isNaiveUpdate;
  @CsvBindByName private SuggestionStatus status;
  @CsvBindByName private List<String> messages;
  // corresponds to the number of libraries with which we have violations
  @CsvBindByName private int nrOfViolations;
  @CsvBindByName private int nrOfViolatedCalls;
  @CsvBindByName private int cutWeight = 1;

  public enum SuggestionStatus {
    EMPTY_CG,
    FAILED_SIGTEST,
    SUCCESS,
    NO_NEO4J_ENTRY
  }
}
