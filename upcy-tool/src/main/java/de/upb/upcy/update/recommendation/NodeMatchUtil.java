package de.upb.upcy.update.recommendation;

import de.upb.maven.ecosystem.persistence.model.DependencyRelation;
import de.upb.maven.ecosystem.persistence.model.MvnArtifactNode;
import de.upb.upcy.base.graph.GraphModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * (Re-)Find libraries in the different graph representations: dependency graph, call graph, update
 * Graph from Neo4j, unified dependency graph
 *
 * @author adann
 */
public class NodeMatchUtil {
  private static final Logger LOGGER = LoggerFactory.getLogger(NodeMatchUtil.class);

  private final GraphModel.Artifact rootNode;
  private final HashMap<String, String> classToGav = new HashMap<>();
  private final HashMap<String, Set<String>> GavToClasses = new HashMap<>();

  public NodeMatchUtil(GraphModel.Artifact rootNode) {
    this.rootNode = rootNode;
  }

  public static Set<String> getClassNamesFromJarFileOrDir(File givenFile) throws IOException {
    Set<String> classNames = new HashSet<>();

    if (givenFile.isFile()
        && (givenFile.getName().endsWith("jar")
            || givenFile.getName().endsWith("war")
            || givenFile.getName().endsWith("zip"))) {

      try (JarFile jarFile = new JarFile(givenFile)) {
        Enumeration<JarEntry> e = jarFile.entries();
        while (e.hasMoreElements()) {
          JarEntry jarEntry = e.nextElement();
          if (jarEntry.getName().endsWith(".class")) {
            String className = jarEntry.getName().replace("/", ".").replace(".class", "");
            classNames.add(className);
          }
        }
        return classNames;
      }
    }
    if (givenFile.isDirectory()) {
      // search directory
      final Path start = givenFile.toPath();
      try (Stream<Path> paths = Files.walk(start)) {
        paths.forEach(
            f -> {
              if (f.getFileName().toString().endsWith(".class")) {
                final Path relativize = start.relativize(f);
                String className = relativize.toString().replace("/", ".").replace(".class", "");
                classNames.add(className);
              }
            });
        return classNames;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    LOGGER.warn("Did not found classes for {}", givenFile);
    return classNames;
  }

  public HashMap<String, String> getClassToGav() {
    return classToGav;
  }

  public HashMap<String, Set<String>> getGavToClasses() {
    return GavToClasses;
  }

  public Optional<String> findInDepGraphByGav(
      GraphModel.Artifact artifact, Graph<String, CustomEdge> in, boolean withVersion) {

    return in.vertexSet().stream().filter(x -> match(artifact, x, withVersion)).findFirst();
  }

  public void computeJarAndClassMapping(Collection<String> runtimeDir) throws IOException {

    // setup compute class mapping
    for (String cpEntry : runtimeDir) {
      final Set<String> classNamesFromJarFile = getClassNamesFromJarFileOrDir(new File(cpEntry));

      String jarFileName = toGav(cpEntry);
      this.GavToClasses.put(jarFileName, classNamesFromJarFile);
      for (String clName : classNamesFromJarFile) {
        classToGav.put(clName, jarFileName);
      }
    }
  }

  public String getFileName(String abFileName) {
    int index = abFileName.lastIndexOf(File.separator);
    String fileName = abFileName;
    if (index > 0) {
      fileName = abFileName.substring(index + 1);
    }

    return fileName;
  }

  public String toGav(GraphModel.Artifact node) {
    return String.join(":", node.getGroupId(), node.getArtifactId(), node.getVersion());
  }

  public String toGav(String abFileName) {
    if ((!abFileName.endsWith("jar") || !abFileName.endsWith("war"))
        && abFileName.endsWith("classes")) {
      // we have classes in the PROJECT_ROOT_NODE
      return String.join(
          ":", rootNode.getGroupId(), rootNode.getArtifactId(), rootNode.getVersion());
    }

    // cut of prefix
    final String prefix = ".m2" + File.separator + "repository" + File.separator;
    int index = abFileName.lastIndexOf(prefix);
    String currentString = abFileName;
    if (index > 0) {
      currentString = abFileName.substring(index + prefix.length());
    }
    // cut of the filename
    index = currentString.lastIndexOf(File.separator);
    if (index > 0) {
      currentString = currentString.substring(0, index + 1);
    }
    final String[] split = currentString.split(File.separator);
    String version = split[split.length - 1];
    String artifact = split[split.length - 2];
    StringBuilder builder = new StringBuilder();

    for (int i = 0; i < split.length - 2; i++) {
      if (StringUtils.isBlank(split[i])) {
        continue;
      }
      builder.append(split[i]);

      if (i != split.length - 3) {
        builder.append(".");
      }
    }

    String group = builder.toString();

    return String.join(":", group, artifact, version);
  }

  public Optional<GraphModel.Artifact> findInDepGraphByGav(
      String gavOfLibraryToUpdate,
      DefaultDirectedGraph<GraphModel.Artifact, GraphModel.Dependency> depGraph,
      boolean withVersion) {

    return depGraph.vertexSet().stream()
        .filter(x -> match(x, gavOfLibraryToUpdate, withVersion))
        .findFirst();
  }

  public Optional<MvnArtifactNode> findInNeo4jGraph(
      GraphModel.Artifact depToCheck,
      Graph<MvnArtifactNode, DependencyRelation> in,
      boolean withVersion) {
    return in.vertexSet().stream()
        .filter(
            x -> {
              final boolean equals = StringUtils.equals(x.getGroup(), depToCheck.getGroupId());
              final boolean equals1 =
                  StringUtils.equals(x.getArtifact(), depToCheck.getArtifactId());
              final boolean equals2 = StringUtils.equals(x.getVersion(), depToCheck.getVersion());
              if (withVersion) {
                return equals & equals1 & equals2;
              } else {
                return equals & equals1;
              }
            })
        .findFirst();
  }

  public Optional<MvnArtifactNode> findLooseInNeo4jGraph(
      GraphModel.Artifact depToCheck,
      Graph<MvnArtifactNode, DependencyRelation> in,
      boolean withVersion) {
    return in.vertexSet().stream()
        .filter(
            x -> {
              final boolean equals = StringUtils.contains(x.getGroup(), depToCheck.getGroupId());
              final boolean equals1 =
                  StringUtils.contains(x.getArtifact(), depToCheck.getArtifactId());
              final boolean equals2 = StringUtils.equals(x.getVersion(), depToCheck.getVersion());
              if (withVersion) {
                return equals & equals1 & equals2;
              } else {
                return equals & equals1;
              }
            })
        .findFirst();
  }

  public Optional<GraphModel.Artifact> findInDepGraph(
      MvnArtifactNode mvnArtifactNode,
      Graph<GraphModel.Artifact, GraphModel.Dependency> dependencyGraph,
      boolean withVersion) {
    return dependencyGraph.vertexSet().stream()
        .filter(
            x -> {
              final boolean equals = StringUtils.equals(x.getGroupId(), mvnArtifactNode.getGroup());
              final boolean equals1 =
                  StringUtils.equals(x.getArtifactId(), mvnArtifactNode.getArtifact());
              final boolean equals2 =
                  StringUtils.equals(x.getVersion(), mvnArtifactNode.getVersion());
              if (withVersion) {
                return equals & equals1 & equals2;
              } else {
                return equals & equals1;
              }
            })
        .findFirst();
  }

  public boolean match(GraphModel.Artifact x, String libInCG, boolean withVersion) {
    final String[] split = libInCG.split(":");

    if (split.length < 3 && withVersion) {
      throw new IllegalArgumentException("no version in gav given");
    }
    if (split.length < 2) {
      throw new IllegalArgumentException("Not a valid gav: " + x);
    }
    String version = null;
    if (split.length == 3) {
      version = split[2];
    }
    String group = split[0];
    String artifact = split[1];
    final boolean equals = StringUtils.equals(group, x.getGroupId());
    final boolean equals1 = StringUtils.equals(artifact, x.getArtifactId());
    final boolean equals2 = StringUtils.equals(version, x.getVersion());
    if (withVersion) {
      return equals & equals1 & equals2;
    } else {
      return equals & equals1;
    }
  }
}
