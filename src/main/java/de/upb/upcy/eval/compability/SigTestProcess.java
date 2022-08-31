package de.upb.upcy.eval.compability;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.upb.upcy.base.commons.ArtifactInfo;
import de.upb.upcy.base.sigtest.MainGenerateSigTest;
import java.io.IOException;
import java.util.Collections;

public class SigTestProcess {

  /**
   * Run in process to separate from logic and kill sigtest after a timeout
   *
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {

    String jsonString = args[0];

    ObjectMapper objectMapper = new ObjectMapper();

    final ArtifactInfo artifactInfo = objectMapper.readValue(jsonString, ArtifactInfo.class);

    MainGenerateSigTest mainGenerateSigTest = new MainGenerateSigTest();
    mainGenerateSigTest.handle(Collections.singletonList(artifactInfo));
  }
}
