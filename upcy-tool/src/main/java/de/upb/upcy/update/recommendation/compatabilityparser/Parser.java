package de.upb.upcy.update.recommendation.compatabilityparser;

import de.upb.upcy.base.sigtest.db.model.sootdiff.CallGraphCheckDoc;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.alg.util.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Parser {
  private static final Logger LOGGER = LoggerFactory.getLogger(Parser.class);

  public static Collection<SigTestIncompatibility> parseSigCheckDocABI(String fileContent)
      throws ParseException {
    final String[] lines = fileContent.split("\\R");
    boolean classBlock = false;
    String currentClassName = null;
    List<String> currentMethods = new ArrayList<>();
    List<String> currentFields = new ArrayList<>();
    List<String> currentInterface = new ArrayList<>();

    List<SigTestIncompatibility> incompatibilityList = new ArrayList<>();
    boolean keepClass = false;
    for (String line : lines) {
      String trimmedLine = line.trim();

      // currently, we only care for breaks, we do not parse the rest of the content
      final String[] s = trimmedLine.split(" ");
      if (s.length == 2 && StringUtils.equals("Class", s[0]) && !classBlock) {
        // we found the class block
        classBlock = true;
        currentClassName = s[1];
        // go to next line
        continue;
      }
      if (classBlock) {
        String[] split = trimmedLine.split(":");
        if (StringUtils.equals("warn", split[0].trim())) {
          // just a warning
          continue;
        } else if (StringUtils.equals("anno", split[0].trim())) {
          // just a annotation
          continue;
        } else if (split.length >= 2) {
          // initialize the arrays

          final String trim = split[1].trim();
          // get the error
          // example: method public boolean
          // java.util.AbstractCollection.add({com.google.common.collect.ForwardingMap%0})
          final int i = trim.indexOf(" ");
          if (i > -1) {
            String type = trim.substring(0, i);
            String qualifier = trim.substring(i + 1);
            if (StringUtils.equals(type, "field")) {
              currentFields.add(qualifier);
            } else if (StringUtils.equals(type, "method")
                || StringUtils.equals(type, "constructor")) {
              currentMethods.add(qualifier);
            } else if (StringUtils.equals(type, "CLASS")) {
              currentMethods = new ArrayList<>();
              currentFields = new ArrayList<>();
              currentInterface = new ArrayList<>();
              keepClass = true;
            } else if (StringUtils.equals(type, "interface")) {
              currentInterface.add(qualifier);
            } else {
              LOGGER.error("Unknown construct type");
            }
          }
        }
      }
      if (StringUtils.isBlank(trimmedLine) && classBlock) {
        // end of class block
        classBlock = false;
        // save the parsing result
        if (currentMethods.isEmpty()
            && currentFields.isEmpty()
            && currentInterface.isEmpty()
            && !keepClass) {
          // we have a dummy class with only warnings or annotations, we don't count that as
          // incompatibilities
          LOGGER.trace("dummy class, skipping");
        } else {
          final SigTestIncompatibility incompatibility =
              new SigTestIncompatibility(
                  currentClassName, currentMethods, currentFields, currentInterface);
          incompatibilityList.add(incompatibility);
        }
        currentClassName = null;
        currentMethods = new ArrayList<>();
        currentFields = new ArrayList<>();
        currentInterface = new ArrayList<>();
        keepClass = false;
      }
      if (trimmedLine.startsWith("STATUS:")) {
        String[] split = trimmedLine.split(":");
        if (StringUtils.equals(split[1], "Passed.")) {
          if (!incompatibilityList.isEmpty()) {
            throw new ParseException("Failed to parse the files!", -1);
          }
        } else if (StringUtils.startsWith(split[1], "Failed.")) {
          // get the number
          int idx1 = split[1].lastIndexOf(".");
          int idx2 = split[1].indexOf(" ");
          int number = Integer.parseInt(split[1].substring(idx1 + 1, idx2));
          int foundViolations =
              incompatibilityList.stream()
                  .mapToInt(
                      x -> {
                        if ((x.getFieldNames() == null || x.getFieldNames().isEmpty())
                            && (x.getMethodNames() == null || x.getMethodNames().isEmpty())
                            && (x.getInterfaceNames() == null || x.getInterfaceNames().isEmpty())) {
                          return 1;
                        } else {
                          return (x.getFieldNames() != null ? x.getFieldNames().size() : 0)
                              + (x.getMethodNames() != null ? x.getMethodNames().size() : 0)
                              + (x.getInterfaceNames() != null ? x.getInterfaceNames().size() : 0);
                        }
                      })
                  .sum();

          if (foundViolations != number) {
            throw new ParseException("Failed to parse the files!", -1);
          }
        }
      }
    }
    return incompatibilityList;
  }

  public static Collection<Incompatibility> parseSigCheckDocSrc(String fileContent)
      throws ParseException {

    // TODO handle lines like
    // io.netty.channel.socket.SocketChannelConfig:                interface @
    // io.netty.channel.socket.DuplexChannelConfig
    final String[] lines = fileContent.split("\\R");

    HashMap<String, Triple<List<String>, List<String>, List<String>>> classMethodFieldSuperType =
        new HashMap<>();

    int emptyLineInBlock = 0;
    SigTestSRCFileBlock currentState = SigTestSRCFileBlock.START;
    for (String line : lines) {

      String trimmedLine = line.trim();
      if (currentState != SigTestSRCFileBlock.START) {
        if (trimmedLine.startsWith("-") && trimmedLine.endsWith("-")) {
          // just the separator char;
          continue;
        }
        if (StringUtils.isBlank(trimmedLine)) {
          emptyLineInBlock++;
          if (emptyLineInBlock == 2) {
            // the block is done
            // reset for next
            emptyLineInBlock = 0;
            currentState = SigTestSRCFileBlock.START;
          }
          // do not parse an empty line, nothing to do
          continue;
        }
      }

      final SigTestSRCFileBlock sigTestSRCFileBlock = SigTestSRCFileBlock.valueOfLabel(trimmedLine);
      if (sigTestSRCFileBlock != null) {
        switch (sigTestSRCFileBlock) {
          case MISS_ClASSES:
            currentState = SigTestSRCFileBlock.MISS_ClASSES;
            continue;
          case MISS_CONST:
            currentState = SigTestSRCFileBlock.MISS_CONST;
            continue;
          case MISS_METHODS:
            currentState = SigTestSRCFileBlock.MISS_METHODS;
            continue;
          case MISS_SUPER:
            currentState = SigTestSRCFileBlock.MISS_SUPER;
            continue;
          default:
            break;
        }
      }

      // parse the text

      switch (currentState) {
        case START:
          break;
        case MISS_CONST:
          {
            String[] split = trimmedLine.split(":");
            if (split.length != 2) {
              LOGGER.error("Cannot parse Missing Constructor entry");
              continue;
            }

            String className = split[0].trim();
            String trim = split[1].trim();

            final int i = trim.indexOf(" ");
            if (i > -1) {
              String type = trim.substring(0, i);
              String qualifier = trim.substring(i + 1);
              final Triple<List<String>, List<String>, List<String>> listListListTriple =
                  classMethodFieldSuperType.computeIfAbsent(
                      className,
                      x -> Triple.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
              listListListTriple.getFirst().add(qualifier);
            }
          }
          break;
        case MISS_METHODS:
          {
            String[] split = trimmedLine.split(":");
            if (split.length != 2) {
              LOGGER.error("Cannot parse Missing Method entry");
              continue;
            }
            String className = split[0].trim();
            String trim = split[1].trim();

            final int i = trim.indexOf(" ");
            if (i > -1) {
              String type = trim.substring(0, i);
              String qualifier = trim.substring(i + 1);
              final Triple<List<String>, List<String>, List<String>> listListListTriple =
                  classMethodFieldSuperType.computeIfAbsent(
                      className,
                      x -> Triple.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
              listListListTriple.getFirst().add(qualifier);
            }
          }
          break;
        case MISS_SUPER:
          {
            String[] split = trimmedLine.split(":");
            if (split.length != 2) {
              LOGGER.error("Cannot parse Missing SuperClass or Interface entry");
              continue;
            }
            String className = split[0].trim();
            String superTypeName = split[1].trim();
            final Triple<List<String>, List<String>, List<String>> listListListTriple =
                classMethodFieldSuperType.computeIfAbsent(
                    className,
                    x -> Triple.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            listListListTriple.getThird().add(superTypeName);
          }
          break;
        case MISS_ClASSES:
          {
            final Triple<List<String>, List<String>, List<String>> listListListTriple =
                classMethodFieldSuperType.computeIfAbsent(
                    trimmedLine,
                    x -> Triple.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
          }
          break;
      }
    }

    // create incompatibilities
    List<Incompatibility> incompatibilityList = new ArrayList<>();
    for (Map.Entry<String, Triple<List<String>, List<String>, List<String>>>
        classNameMethodFieldSuperTypeEntry : classMethodFieldSuperType.entrySet()) {

      final String clName = classNameMethodFieldSuperTypeEntry.getKey();
      final Triple<List<String>, List<String>, List<String>> value =
          classNameMethodFieldSuperTypeEntry.getValue();
      Incompatibility incompatibility =
          new SigTestIncompatibility(clName, value.getFirst(), value.getSecond(), value.getThird());
      incompatibilityList.add(incompatibility);
    }

    return incompatibilityList;
  }

  public static Collection<Incompatibility> parseSootDiffDoc(
      List<CallGraphCheckDoc.MethodCGAPI> brokenMethodsSignature) {
    List<Incompatibility> result = new ArrayList<>();
    for (CallGraphCheckDoc.MethodCGAPI methodCGAPI : brokenMethodsSignature) {
      result.add(new SootMethodIncompatibility(methodCGAPI));
    }
    return result;
  }

  public enum COMPATABILITY_TYPE {
    SRC(1),
    BINARY(2),
    SEMANTIC(4);
    private final int value;

    COMPATABILITY_TYPE(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public enum SigTestSRCFileBlock {
    MISS_SUPER("Missing Superclasses or Superinterfaces"),
    MISS_ClASSES("Missing Classes"),
    MISS_METHODS("Missing Methods"),
    MISS_CONST("Missing Constructors"),
    START("");

    private final String value;

    SigTestSRCFileBlock(String value) {
      this.value = value;
    }

    public static SigTestSRCFileBlock valueOfLabel(String label) {
      for (SigTestSRCFileBlock e : SigTestSRCFileBlock.values()) {
        if (e.value.equals(label)) {
          return e;
        }
      }
      return null;
    }
  }
}
