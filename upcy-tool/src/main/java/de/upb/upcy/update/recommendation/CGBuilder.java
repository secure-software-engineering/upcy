package de.upb.upcy.update.recommendation;

import com.google.common.base.Stopwatch;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.G;
import soot.MethodOrMethodContext;
import soot.PackManager;
import soot.Scene;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.options.Options;

/**
 * Create the module's call graph using the static analysis framework Soot
 * https://github.com/soot-oss/soot
 *
 * @author adann
 */
public class CGBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(CGBuilder.class);

  private final Collection<String> classPathDirs;
  private final Collection<String> applicationClassDir;
  private final NodeMatchUtil nodeMatchUtil;
  private SootCallGraphAdapter sootCallGraphAdapter;
  private Graph<String, CustomEdge> shrinkedCG;

  public CGBuilder(
      Collection<String> runtimeDir,
      Collection<String> applicationClassDir,
      NodeMatchUtil nodeMatchUtil) {
    this.classPathDirs = runtimeDir;
    this.applicationClassDir = applicationClassDir;
    this.nodeMatchUtil = nodeMatchUtil;
  }

  public SootCallGraphAdapter getSootCallGraphAdapter() {
    if (sootCallGraphAdapter == null) {
      throw new IllegalStateException("Run compute first");
    }
    return sootCallGraphAdapter;
  }

  public Graph<String, CustomEdge> getShrinkedCG() {
    if (shrinkedCG == null) {
      throw new IllegalStateException("Run compute first");
    }
    return shrinkedCG;
  }

  private void setUpSoot(
      Collection<String> packageNames, List<String> classPath, List<String> applicationClassDir) {
    LOGGER.info("[Soot] Setting Up Soot ... \n");

    G.reset();
    G.v().resetSpark();
    Options.v().set_debug(true);
    Options.v().set_debug_resolver(true);
    Options.v().set_ignore_resolution_errors(true);
    Options.v().set_ignore_resolving_levels(true);
    Options.v().set_ignore_classpath_errors(true);
    Options.v().set_keep_line_number(true);
    Options.v().set_whole_program(true);
    // only use class files
    Options.v().set_src_prec(Options.src_prec_only_class);

    Options.v().set_no_bodies_for_excluded(true);
    Options.v().set_allow_phantom_refs(true);

    Options.v().setPhaseOption("cg", "enabled:" + true);
    Options.v().setPhaseOption("cg.cha", "enabled:" + true);

    // prepend the default classpath
    Options.v().set_prepend_classpath(true);

    // configure the application classes via the process dir
    Options.v().set_process_dir(applicationClassDir);

    Options.v().set_soot_classpath(String.join(File.pathSeparator, classPath));
    Options.v().set_no_writeout_body_releasing(true);

    // set the application classes
    Options.v().set_include(new ArrayList<>(packageNames));

    Options.v().setPhaseOption("cg", "verbose:" + false);
    Options.v().setPhaseOption("cg", "safe-forname:" + false);
    Options.v().setPhaseOption("cg", "all-reachable:" + true);
  }

  public void computeCGs(Collection<String> appPKGs) {

    LOGGER.info("[Analysis] START Analysis of " + classPathDirs);

    LOGGER.info("[Setup] Started \n");
    Stopwatch stopwatch = Stopwatch.createStarted();

    setUpSoot(
        appPKGs, new ArrayList<>(this.classPathDirs), new ArrayList<>(this.applicationClassDir));

    stopwatch.stop();
    LOGGER.info("[Setup] Complete {}", stopwatch.elapsed(TimeUnit.SECONDS));

    LOGGER.info("[Analysis] Start \n");

    stopwatch.reset();
    stopwatch.start();

    Scene.v().loadNecessaryClasses();
    PackManager.v().runPacks();
    LOGGER.info("[Analysis] Took {}", stopwatch.elapsed(TimeUnit.SECONDS));
    stopwatch.stop();

    final CallGraph callGraph = Scene.v().getCallGraph();

    sootCallGraphAdapter = new SootCallGraphAdapter(callGraph);
    shrinkedCG = shrinkCG(sootCallGraphAdapter);
  }

  private @NotNull Graph<String, CustomEdge> shrinkCG(
      @NotNull SootCallGraphAdapter sootCallGraphAdapter) {
    Graph<String, CustomEdge> shrinkedGraph = new DefaultDirectedGraph<>(CustomEdge.class);
    // create the nodes for the jars on the classpath

    // create nodes for the jars and the project
    this.classPathDirs.forEach(
        abFileName -> shrinkedGraph.addVertex(nodeMatchUtil.toGav(abFileName)));
    this.applicationClassDir.forEach(
        abFileName -> shrinkedGraph.addVertex(nodeMatchUtil.toGav(abFileName)));

    // iterate through the callgraph and add the edges to the shrinked
    final Iterator<Edge> iterator = sootCallGraphAdapter.getCallGraph().iterator();
    while (iterator.hasNext()) {
      final Edge cgEdge = iterator.next();
      final MethodOrMethodContext src = cgEdge.getSrc();
      final MethodOrMethodContext tgt = cgEdge.getTgt();
      // add the edge
      final String srcDeclClassName = src.method().getDeclaringClass().getName();
      final String tgtDeclClassName = tgt.method().getDeclaringClass().getName();
      final String srcVertexLib = nodeMatchUtil.getClassToGav().get(srcDeclClassName);
      final String targetVertexLib = nodeMatchUtil.getClassToGav().get(tgtDeclClassName);
      CustomEdge edge = shrinkedGraph.getEdge(srcVertexLib, targetVertexLib);
      if (edge == null && srcVertexLib != null && targetVertexLib != null) {
        edge = new CustomEdge();
        shrinkedGraph.addEdge(srcVertexLib, targetVertexLib, edge);
        // add the method to the edge
        edge.getSrcTgtMethods().add(Pair.of(src.method(), tgt.method()));
      } else if (edge != null) {
        // add the method to the edge
        edge.getSrcTgtMethods().add(Pair.of(src.method(), tgt.method()));
      }
    }

    return shrinkedGraph;
  }
}
