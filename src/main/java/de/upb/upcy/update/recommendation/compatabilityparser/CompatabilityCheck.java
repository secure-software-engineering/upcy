package de.upb.upcy.update.recommendation.compatabilityparser;

import de.upb.upcy.base.commons.ArtifactInfo;
import de.upb.upcy.base.commons.CompressionUtils;
import de.upb.upcy.base.compatibility.MainComputeIncompatibilities;
import de.upb.upcy.base.sigtest.db.MongoDBHandler;
import de.upb.upcy.base.sigtest.db.model.check.SigTestCheckDBDoc;
import de.upb.upcy.base.sigtest.db.model.generate.SigTestDBDoc;
import de.upb.upcy.base.sigtest.db.model.sootdiff.CallGraphCheckDoc;
import de.upb.upcy.update.recommendation.exception.CompatabilityComputeException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Get the incompatibilities between two versions of a library, using SigTest and the MongoDB with
 * API incompatibilities
 *
 * @author adann
 */
public class CompatabilityCheck {

  public static final Logger LOGGER = LoggerFactory.getLogger(CompatabilityCheck.class);

  private static CompatabilityCheck instance = null;
  private final MongoDBHandler mongoDBHandler = MongoDBHandler.getInstance();

  private CompatabilityCheck() {}

  public static CompatabilityCheck getInstance() {
    if (instance == null) {
      instance = new CompatabilityCheck();
    }
    return instance;
  }

  private static void generateSigTest(
      SigTestDBDoc baseVersion, SigTestDBDoc nextVersion, int mode) {
    final ArtifactInfo baseArtifact = baseVersion.getArtifactInfo();
    final ArtifactInfo nextArtifact = nextVersion.getArtifactInfo();
    generateSigTest(
        baseArtifact.getGroupId(),
        baseArtifact.getArtifactId(),
        baseArtifact.getVersion(),
        nextArtifact.getGroupId(),
        nextArtifact.getArtifactId(),
        nextArtifact.getVersion(),
        mode);
  }

  private static void generateSigTest(
      String baseGroup,
      String baseArtifact,
      String baseVersion,
      String nextGroup,
      String nextArtifact,
      String nextVersion,
      int mode) {
    try {
      // create the input file

      Path path = Files.createTempFile("tmp", "inputSig");
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write(
            "group,artifact,version,new_group,new_artifact,new_version" + System.lineSeparator());
        writer.write(
            baseGroup
                + ","
                + baseArtifact
                + ","
                + baseVersion
                + ","
                + nextGroup
                + ","
                + nextArtifact
                + ","
                + nextVersion);
      }
      MainComputeIncompatibilities mainComputeInCompatibilities =
          new MainComputeIncompatibilities(path);

      if ((mode & SigGenerateMode.SIGTEST.id) != 0) {
        mainComputeInCompatibilities.generateSignatures();
      }

      if ((mode & SigGenerateMode.ABI.id) != 0) {

        mainComputeInCompatibilities.compareABI();
      }

      if ((mode & SigGenerateMode.SOOTDIFF.id) != 0) {

        mainComputeInCompatibilities.compareSootDiff();
      }
      if ((mode & SigGenerateMode.SOURCE.id) != 0) {

        mainComputeInCompatibilities.compareSource();
      }

    } catch (IOException exception) {
      LOGGER.error("Failed to create file for signature generation", exception);
    }
  }

  private <T> T filterDuplicates(Iterable<T> iterable) {
    List<T> result = new ArrayList<>();
    iterable.forEach(result::add);
    if (result.size() > 1) {
      LOGGER.debug("List contains duplicate documents");
      return result.get(0);
    } else if (result.isEmpty()) {
      return null;
    }
    return result.get(0);
  }

  public Map<Parser.COMPATABILITY_TYPE, Collection<? extends Incompatibility>> getCompatabilityInfo(
      String baseGroup,
      String baseArtifact,
      String baseVersion,
      String nextGroup,
      String nextArtifact,
      String nextVersion)
      throws CompatabilityComputeException {
    MainComputeIncompatibilities mainComputeInCompatibilities = null;

    SigTestDBDoc base = mongoDBHandler.findBy(baseGroup, baseArtifact, baseVersion);
    SigTestDBDoc next = mongoDBHandler.findBy(nextGroup, nextArtifact, nextVersion);

    // if not found compute the signature for the comparison
    if (base == null || next == null) {
      generateSigTest(
          baseGroup,
          baseArtifact,
          baseVersion,
          nextGroup,
          nextArtifact,
          nextVersion,
          SigGenerateMode.SIGTEST.id
              | SigGenerateMode.SOOTDIFF.id
              | SigGenerateMode.ABI.id
              | SigGenerateMode.SOURCE.id);
    }

    // query db again
    base = mongoDBHandler.findBy(baseGroup, baseArtifact, baseVersion);
    next = mongoDBHandler.findBy(nextGroup, nextArtifact, nextVersion);

    if (next == null || base == null) {
      LOGGER.error(
          "Could not create compatibility information for {} and {}",
          baseGroup + ":" + baseArtifact + ":" + baseVersion,
          nextGroup + ":" + nextArtifact + ":" + nextVersion);
      throw new CompatabilityComputeException(
          "Could not compute compatability for : "
              + baseGroup
              + ":"
              + baseArtifact
              + ":"
              + baseVersion
              + " / "
              + nextGroup
              + ":"
              + nextArtifact
              + ":"
              + nextVersion);
    }

    try {
      return getCompatabilityInfo(base, next);
    } catch (IOException e) {
      LOGGER.error("Failed to get Compatability Info", e);
    }
    return Collections.emptyMap();
  }

  private Map<Parser.COMPATABILITY_TYPE, Collection<? extends Incompatibility>>
      getCompatabilityInfo(SigTestDBDoc baseVersion, SigTestDBDoc nextVersion) throws IOException {

    HashMap<Parser.COMPATABILITY_TYPE, Collection<? extends Incompatibility>> result =
        new HashMap<>();

    LOGGER.info("Stats for {} : {}", baseVersion.getArtifactInfo(), nextVersion.getArtifactInfo());

    Iterable<SigTestCheckDBDoc> sigCheckIter =
        mongoDBHandler.findSigCheck(baseVersion, nextVersion);

    SigTestCheckDBDoc sigTestCheckDBDoc = filterDuplicates(sigCheckIter);

    if (sigTestCheckDBDoc == null) {
      // re-run
      generateSigTest(baseVersion, nextVersion, SigGenerateMode.ABI.id);
      sigCheckIter = mongoDBHandler.findSigCheck(baseVersion, nextVersion);

      sigTestCheckDBDoc = filterDuplicates(sigCheckIter);
    }

    if (sigTestCheckDBDoc != null) {
      String s = CompressionUtils.decompressB64(sigTestCheckDBDoc.getReportFileContent());
      final Collection<? extends Incompatibility> incompatibilities;
      try {
        incompatibilities = Parser.parseSigCheckDocABI(s);
        result.put(Parser.COMPATABILITY_TYPE.BINARY, incompatibilities);
      } catch (ParseException e) {
        LOGGER.error("Failed to parse SigTest ABI Doc", e);
      }
    }

    Iterable<SigTestCheckDBDoc> sigCheckSourceIter =
        mongoDBHandler.findSigCheckSource(baseVersion, nextVersion);
    SigTestCheckDBDoc sigCheckSource = filterDuplicates(sigCheckSourceIter);

    if (sigCheckSource == null) {
      // re-run
      generateSigTest(baseVersion, nextVersion, SigGenerateMode.SOURCE.id);

      sigCheckSourceIter = mongoDBHandler.findSigCheckSource(baseVersion, nextVersion);
      sigCheckSource = filterDuplicates(sigCheckSourceIter);
    }

    if (sigCheckSource != null) {
      String s = CompressionUtils.decompressB64(sigCheckSource.getReportFileContent());
      try {
        final Collection<Incompatibility> incompatibilities = Parser.parseSigCheckDocSrc(s);
        result.put(Parser.COMPATABILITY_TYPE.SRC, incompatibilities);
      } catch (ParseException e) {
        LOGGER.error("Failed to parse SigTest Src Doc", e);
      }
    }

    Iterable<CallGraphCheckDoc> sootDiffCGCheck =
        mongoDBHandler.findSootDiffCGCheck(baseVersion, nextVersion);
    CallGraphCheckDoc sootDiffResults = filterDuplicates(sootDiffCGCheck);

    if (sootDiffResults == null) {
      // re-run
      generateSigTest(baseVersion, nextVersion, SigGenerateMode.SOOTDIFF.id);
      sootDiffCGCheck = mongoDBHandler.findSootDiffCGCheck(baseVersion, nextVersion);
      sootDiffResults = filterDuplicates(sootDiffCGCheck);
    }

    if (sootDiffResults != null) {

      final Collection<Incompatibility> incompatibilities =
          Parser.parseSootDiffDoc(sootDiffResults.getBrokenMethodsSignature());
      result.put(Parser.COMPATABILITY_TYPE.SEMANTIC, incompatibilities);
    }

    return result;
  }

  private enum SigGenerateMode {
    SIGTEST(1),
    ABI(2),
    SOOTDIFF(4),
    SOURCE(8);
    private final int id;

    SigGenerateMode(int id) {
      this.id = id;
    }
  }
}
