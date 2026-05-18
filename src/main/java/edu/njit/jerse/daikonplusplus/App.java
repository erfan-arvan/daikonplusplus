package edu.njit.jerse.daikonplusplus;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import edu.njit.jerse.daikonplusplus.config.*;
import edu.njit.jerse.daikonplusplus.filter.TestInvariantFilter;
import edu.njit.jerse.daikonplusplus.inject.FileWriteCoordinator;
import edu.njit.jerse.daikonplusplus.inject.JavaParserInjector;
import edu.njit.jerse.daikonplusplus.llm.LlmInvariantGenerator;
import edu.njit.jerse.daikonplusplus.model.*;
import edu.njit.jerse.daikonplusplus.parse.JavaProjectScanner;
import edu.njit.jerse.daikonplusplus.parse.context.ContextKind;
import edu.njit.jerse.daikonplusplus.parse.context.ContextUtils;
import edu.njit.jerse.daikonplusplus.results.InvariantRegistry;
import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * Entry point for the Daikon++ pipeline.
 *
 * <p>This class orchestrates the full end-to-end workflow of LLM-guided invariant inference,
 * including:
 *
 * <ol>
 *   <li><b>Program analysis:</b> Scanning Java source files to extract program points (currently
 *       method ENTRY and EXIT).
 *   <li><b>Invariant proposal:</b> Querying an LLM to generate candidate invariants for each
 *       program point using configurable context.
 *   <li><b>Injection:</b> Instrumenting source files by inserting invariant checks as guard code.
 *   <li><b>Compilation and execution:</b> Compiling the instrumented project and executing it
 *       (either natively or via an external project runner such as Gradle).
 *   <li><b>Dynamic validation:</b> Observing invariant outcomes (held, falsified, non-compiled, or
 *       never executed) from execution logs.
 *   <li><b>Optional test-based filtering:</b> Removing spurious invariants using test-driven
 *       refinement.
 * </ol>
 *
 * <h2>Execution Modes</h2>
 *
 * <ul>
 *   <li><b>NATIVE:</b> Compiles and runs Java sources directly using {@code javac/java}.
 *   <li><b>EXTERNAL_PROJECT:</b> Operates on a full project (e.g., Gradle/Maven) using a
 *       user-provided runner script.
 * </ul>
 *
 * <h2>Key Design Properties</h2>
 *
 * <ul>
 *   <li><b>Soundness-oriented filtering:</b> Invariants are validated by execution; any invariant
 *       that is falsified is discarded.
 *   <li><b>Compilation-aware filtering:</b> Invariants that fail to compile are automatically
 *       removed through iterative recompilation.
 *   <li><b>Deterministic tracking:</b> Each invariant is assigned a unique identifier and tracked
 *       across compilation and execution phases via a registry.
 *   <li><b>Isolation via working copies:</b> All transformations occur on temporary copies of the
 *       input project to avoid modifying the original sources.
 * </ul>
 *
 * <h2>Pipeline Overview</h2>
 *
 * <pre>
 * scan → LLM proposal → inject → compile (auto-filter) → execute → log parsing → outcome classification
 * </pre>
 *
 * <p>The final output includes per-invariant outcomes and grouped summaries by method.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * java -jar daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]
 * }</pre>
 *
 * <p>See CLI help for full invocation formats including external-project mode.
 */
public final class App {

  private static final DpConfig BASE_CFG = DpConfig.fromEnv();

  private static final boolean DEBUG = BASE_CFG.debug();

  private enum ExecMode {
    NATIVE,
    EXTERNAL_PROJECT
  }

  private static final java.util.Set<String> RUN_DEDUP =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static String keyFor(ProgramPoint pt, String expr) {
    String norm = expr.trim().replaceAll("\\s+", " "); // normalize whitespace
    return pt.kind().name() + "|" + pt.elementId().toString() + "|" + norm;
  }

  public static void main(String[] args) throws Exception {
    // reset per pipeline invocation
    RUN_DEDUP.clear();
    if (BASE_CFG.debug()) {
      BASE_CFG.printSummary();
    }

    final Path externalMainCompileScript =
        Optional.ofNullable(BASE_CFG.compileMainScript()).map(Path::of).orElse(null);

    final Path externalTestCompileScript =
        Optional.ofNullable(BASE_CFG.compileTestScript()).map(Path::of).orElse(null);

    // Detect external-project mode early so we don't enforce args.length>=3 for that mode.
    final boolean externalMode =
        java.util.Arrays.asList(args).contains("--cmd")
            || java.util.Arrays.asList(args).contains("--external-project");

    if (!externalMode && args.length < 3) {
      System.err.println(
          "Usage:\n"
              + "  Single-root (legacy):\n"
              + "    java -jar daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]\n"
              + "  Split main/test:\n"
              + "    java -jar daikonplusplus.jar <mainSrcRoot> <testSrcRoot> "
              + "<mainClasspath> <testClasspath> <testMainClass> [maxK] [-- program args...]\n"
              + "  External-project:\n"
              + "    java -jar daikonplusplus.jar --external-project "
              + "--project-root <projectRoot> --main-src <relMainSrc> "
              + "[--test-src <relTestSrc>] --runner-script <script.sh> "
              + "[maxK] [-- program args...]");
      System.exit(2);
    }

    ExecMode execMode = externalMode ? ExecMode.EXTERNAL_PROJECT : ExecMode.NATIVE;
    System.out.println("🔥 EXEC MODE = " + execMode);

    // ============================================================
    // External-project arguments (ONLY used/declared as non-null in external branch)
    // ============================================================
    final Path userProjectRoot;
    final Path relMainSrc;
    final Path relTestSrcOrNull;
    final Path runnerScriptPath; // may be relative to project root or absolute

    // ============================================================
    // Native mode variables (kept exactly as your original semantics)
    // ============================================================
    boolean splitMode = false;
    int i = 0;

    Path userMainSrcRoot_tmp = Path.of(".").toAbsolutePath().normalize();
    Path userTestSrcRoot_tmp = userMainSrcRoot_tmp;
    String mainClasspath_tmp = "";
    String testClasspath_tmp = "";
    String entryClass_tmp = "";

    // We'll build a mutable argv view for BOTH modes so we can keep maxK + "-- args" behavior.
    final java.util.List<String> argv = new java.util.ArrayList<>(java.util.List.of(args));

    if (execMode == ExecMode.EXTERNAL_PROJECT) {
      // --- E2E / simple external mode: <inputDir> --cmd <script> [args...]
      if (argv.contains("--cmd") && !argv.contains("--project-root")) {
        int cmdIdx = argv.indexOf("--cmd");

        Path inputDir = Path.of(argv.get(0)).toAbsolutePath().normalize();
        List<String> cmd = new ArrayList<>(argv.subList(cmdIdx + 1, argv.size()));

        if (cmd.isEmpty()) {
          throw new IllegalArgumentException("--cmd requires a command");
        }

        userProjectRoot = inputDir;
        relMainSrc = Path.of(".");
        relTestSrcOrNull = null;
        runnerScriptPath = Path.of(cmd.get(0));

        argv.subList(cmdIdx, argv.size()).clear(); // remove --cmd + command
        argv.remove(0); // remove inputDir

      } else {
        // ----- FLAG-BASED external mode (existing logic) -----
        argv.remove("--external-project");

        Path pr = null;
        Path ms = null;
        Path ts = null;
        Path rs = null;

        for (int k = 0; k < argv.size(); k++) {
          String a = argv.get(k);
          if ("--project-root".equals(a) && k + 1 < argv.size()) {
            pr = Path.of(argv.get(k + 1)).toAbsolutePath().normalize();
            argv.remove(k);
            argv.remove(k);
            k--;
          } else if ("--main-src".equals(a) && k + 1 < argv.size()) {
            ms = Path.of(argv.get(k + 1));
            argv.remove(k);
            argv.remove(k);
            k--;
          } else if ("--test-src".equals(a) && k + 1 < argv.size()) {
            ts = Path.of(argv.get(k + 1));
            argv.remove(k);
            argv.remove(k);
            k--;
          } else if ("--runner-script".equals(a) && k + 1 < argv.size()) {
            rs = Path.of(argv.get(k + 1));
            argv.remove(k);
            argv.remove(k);
            k--;
          }
        }

        if (pr == null || ms == null || rs == null) {
          throw new IllegalArgumentException("Invalid external-project invocation");
        }

        userProjectRoot = pr;
        relMainSrc = ms;
        relTestSrcOrNull = ts;
        runnerScriptPath = rs;
      }

    } else {
      // ============================
      // Native mode parsing (UNCHANGED)
      // ============================
      userProjectRoot = Path.of("."); // unused in native mode (non-null)
      relMainSrc = Path.of("."); // unused in native mode (non-null)
      relTestSrcOrNull = null; // unused
      runnerScriptPath = Path.of("."); // unused in native mode (non-null)

      // Decide whether we are in split main/test mode or single-root mode.
      if (argv.size() >= 5) {
        Path maybeMain = Path.of(argv.get(0)).toAbsolutePath().normalize();
        Path maybeTest = Path.of(argv.get(1)).toAbsolutePath().normalize();
        if (Files.isDirectory(maybeMain) && Files.isDirectory(maybeTest)) {
          splitMode = true;
        }
      }

      if (splitMode) {
        userMainSrcRoot_tmp = Path.of(argv.get(i++)).toAbsolutePath().normalize();
        userTestSrcRoot_tmp = Path.of(argv.get(i++)).toAbsolutePath().normalize();
        if (!Files.isDirectory(userMainSrcRoot_tmp)) {
          System.err.println("Not a directory (main sources): " + userMainSrcRoot_tmp);
          System.exit(2);
        }
        if (!Files.isDirectory(userTestSrcRoot_tmp)) {
          System.err.println("Not a directory (test sources): " + userTestSrcRoot_tmp);
          System.exit(2);
        }

        mainClasspath_tmp = argv.get(i++);
        testClasspath_tmp = argv.get(i++);
        entryClass_tmp = argv.get(i++);
      } else {
        userMainSrcRoot_tmp = Path.of(argv.get(i++)).toAbsolutePath().normalize();
        if (!Files.isDirectory(userMainSrcRoot_tmp)) {
          System.err.println("Not a directory: " + userMainSrcRoot_tmp);
          System.exit(2);
        }
        userTestSrcRoot_tmp = userMainSrcRoot_tmp;
        mainClasspath_tmp = argv.get(i++);
        testClasspath_tmp = "";
        entryClass_tmp = argv.get(i++);
      }
    }

    // Freeze native-mode finals (same names as your original)
    final Path userMainSrcRoot = userMainSrcRoot_tmp;
    final Path userTestSrcRoot = userTestSrcRoot_tmp;
    final String mainClasspath = mainClasspath_tmp;
    final String testClasspath = testClasspath_tmp;
    final String entryClass = entryClass_tmp;

    // ============================================================
    // maxK + program args (UNCHANGED semantics)
    // (Works in both modes: external mode still uses maxK for LLM proposals)
    // ============================================================
    final int maxK;

    if (execMode == ExecMode.EXTERNAL_PROJECT) {
      // External mode: maxK may appear as a trailing integer, otherwise default
      int parsed = 5;
      if (!argv.isEmpty() && argv.get(0).matches("\\d+")) {
        parsed = Math.max(1, Integer.parseInt(argv.remove(0)));
      }
      maxK = parsed;
    } else {
      // Native mode (unchanged)
      maxK =
          (i < argv.size() && !argv.get(i).equals("--"))
              ? Math.max(1, Integer.parseInt(argv.get(i++)))
              : 5;
    }

    final List<String> programArgs = new ArrayList<>();
    if (i < argv.size() && argv.get(i).equals("--")) {
      for (i = i + 1; i < argv.size(); i++) {
        programArgs.add(argv.get(i));
      }
    }

    // ============================================================
    // Prepare working copies
    // ============================================================
    final Path mainSrcRoot;
    final Path testSrcRoot;
    final Path workProjectRoot; // non-null in external mode; equals mainSrcRoot in native mode for
    // simplicity

    if (execMode == ExecMode.EXTERNAL_PROJECT) {
      // FIX: external E2E runs should copy ONLY the provided input directory
      // NOT the entire project root

      // CORRECT: copy the ENTIRE project
      workProjectRoot = prepareWorkingCopy(userProjectRoot);

      // Main/test roots are SUBPATHS inside the copied project
      mainSrcRoot = workProjectRoot.resolve(relMainSrc).normalize();

      testSrcRoot =
          (relTestSrcOrNull == null)
              ? mainSrcRoot
              : workProjectRoot.resolve(relTestSrcOrNull).normalize();

      if (!Files.isDirectory(mainSrcRoot)) {
        throw new IllegalStateException("Main src not found in working project: " + mainSrcRoot);
      }
      if (!Files.isDirectory(testSrcRoot)) {
        throw new IllegalStateException("Test src not found in working project: " + testSrcRoot);
      }

      if (!Files.isDirectory(mainSrcRoot)) {
        System.err.println("Not a directory (main src under project working copy): " + mainSrcRoot);
        System.exit(2);
      }
      if (!Files.isDirectory(testSrcRoot)) {
        System.err.println("Not a directory (test src under project working copy): " + testSrcRoot);
        System.exit(2);
      }

    } else {
      // Native behavior: copy source trees (exactly as before)
      mainSrcRoot = prepareWorkingCopy(userMainSrcRoot);
      testSrcRoot = splitMode ? prepareWorkingCopy(userTestSrcRoot) : mainSrcRoot;
      workProjectRoot =
          mainSrcRoot; // non-null placeholder; not used as "project root" in native mode

      if (!Files.isDirectory(mainSrcRoot)) {
        System.err.println("Not a directory (main working copy): " + mainSrcRoot);
        System.exit(2);
      }
      if (!Files.isDirectory(testSrcRoot)) {
        System.err.println("Not a directory (test working copy): " + testSrcRoot);
        System.exit(2);
      }
    }

    final DpConfig cfg = DpConfig.fromEnv();
    if (!cfg.scanIncludes().isEmpty()) {
      System.out.println(">>> Scan include filter: " + cfg.scanIncludes());
    }
    if (BASE_CFG.registryReset()) {
      try {
        java.nio.file.Files.deleteIfExists(BASE_CFG.registryPath());
        System.out.println(">>> Registry reset: " + BASE_CFG.registryPath().toAbsolutePath());
      } catch (java.io.IOException ioe) {
        System.err.println("Warning: couldn't delete registry: " + ioe.getMessage());
      }
    }

    final JavaProjectScanner scanner = new JavaProjectScanner();
    final LlmInvariantGenerator llm = new LlmInvariantGenerator(BASE_CFG, maxK);
    final InvariantRegistry registry = new InvariantRegistry(cfg.registryPath());
    final JavaParserInjector injector = new JavaParserInjector(new FileWriteCoordinator());

    System.out.println("[DP-PATHS] execMode=" + execMode);
    System.out.println("[DP-PATHS] userProjectRoot=" + userProjectRoot);
    System.out.println("[DP-PATHS] mainSrcRoot=" + mainSrcRoot);
    System.out.println("[DP-PATHS] testSrcRoot=" + testSrcRoot);

    // ============================================================
    // 🔥 SETUP SYMBOL SOLVER (REQUIRED FOR TYPE RESOLUTION)
    // ============================================================

    CombinedTypeSolver solver = new CombinedTypeSolver();

    solver.add(new ReflectionTypeSolver());
    solver.add(new JavaParserTypeSolver(mainSrcRoot.toFile()));

    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);

    com.github.javaparser.ParserConfiguration config =
        new com.github.javaparser.ParserConfiguration()
            .setSymbolResolver(symbolSolver)
            .setLanguageLevel(
                com.github.javaparser.ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

    StaticJavaParser.setConfiguration(config);

    // Scan only MAIN sources for program points
    System.out.println(">>> Scanning MAIN sources under (WORKING COPY): " + mainSrcRoot);
    final List<ProgramPoint> allPoints = scanner.scanMethodEntryExit(mainSrcRoot);

    final Set<String> scanIncludes = cfg.scanIncludes();

    final List<ProgramPoint> points =
        scanIncludes.isEmpty()
            ? allPoints
            : allPoints.stream()
                .filter(pt -> isIncludedByScanFilter(pt.elementId().filePath(), scanIncludes))
                .toList();

    if (!scanIncludes.isEmpty()) {
      System.out.println(">>> Scan include filter: " + scanIncludes);
      System.out.println(">>> Points before filter: " + allPoints.size());
      System.out.println(">>> Points after filter: " + points.size());
    }

    long nEntry = points.stream().filter(p -> p.kind() == ProgramPointKind.METHOD_ENTRY).count();
    long nExit = points.stream().filter(p -> p.kind() == ProgramPointKind.METHOD_EXIT).count();

    System.out.println(
        ">>> Points — ENTRY: " + nEntry + "  EXIT: " + nExit + "  TOTAL: " + points.size());

    // --- Phase 1: parallel LLM proposals ---
    final ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
    final CompletionService<List<InvariantRecord>> ecs = new ExecutorCompletionService<>(pool);
    final List<Future<List<InvariantRecord>>> allFutures = new ArrayList<>();

    for (ProgramPoint pt : points) {
      allFutures.add(ecs.submit(() -> processPoint(pt, mainSrcRoot, llm, registry)));
    }

    final Map<Path, List<InvariantRecord>> byFile = new ConcurrentHashMap<>();
    int submitted = allFutures.size();
    int received = 0;
    int totalSpecs = 0;

    final long totalTimeoutSec = BASE_CFG.llmTotalTimeoutSec();
    final long pollStepMs = BASE_CFG.llmPollStepMs();
    final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalTimeoutSec);
    while (received < submitted) {
      long remainingNs = deadlineNs - System.nanoTime();
      if (remainingNs <= 0) {
        System.err.println(
            "LLM phase timed out; proceeding with completed tasks: " + received + "/" + submitted);
        break;
      }
      Future<List<InvariantRecord>> f =
          ecs.poll(
              Math.min(remainingNs, TimeUnit.MILLISECONDS.toNanos(pollStepMs)),
              TimeUnit.NANOSECONDS);

      if (f == null) {
        System.out.println(
            "... waiting on LLM tasks: "
                + received
                + "/"
                + submitted
                + " done ("
                + TimeUnit.NANOSECONDS.toSeconds(remainingNs)
                + "s left)");
        continue;
      }

      try {
        List<InvariantRecord> recs = f.get();
        received++;
        if (recs == null || recs.isEmpty()) continue;
        totalSpecs += recs.size();
        Path file = mainSrcRoot.resolve(recs.get(0).sourceFile()).normalize();
        byFile
            .computeIfAbsent(file, __ -> Collections.synchronizedList(new ArrayList<>()))
            .addAll(recs);
      } catch (ExecutionException ee) {
        received++;
        Throwable cause = ee.getCause();
        String msg =
            (cause == null)
                ? ee.toString()
                : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        System.err.println("LLM task failed: " + msg);
      }
    }

    for (Future<List<InvariantRecord>> f : allFutures) {
      if (!f.isDone()) f.cancel(true);
    }
    pool.shutdownNow();

    System.out.println(">>> Proposed invariant expressions (post-parse filter): " + totalSpecs);
    System.out.println(">>> Files to inject (MAIN only): " + byFile.size());

    long injectEntry =
        byFile.values().stream()
            .flatMap(List::stream)
            .filter(r -> r.point().kind() == ProgramPointKind.METHOD_ENTRY)
            .count();
    long injectExit =
        byFile.values().stream()
            .flatMap(List::stream)
            .filter(r -> r.point().kind() == ProgramPointKind.METHOD_EXIT)
            .count();
    System.out.println(">>> To inject — ENTRY: " + injectEntry + "  EXIT: " + injectExit);

    // --- Phase 2: Injection on MAIN working copy ---
    final ExecutorService injPool = Executors.newFixedThreadPool(Math.min(cfg.threads(), 8));
    final List<Future<?>> injFutures = new ArrayList<>();
    for (Map.Entry<Path, List<InvariantRecord>> e : byFile.entrySet()) {
      Path file = e.getKey();
      List<InvariantRecord> recs = e.getValue();
      injFutures.add(
          injPool.submit(
              () -> {
                injector.injectGuards(file, recs);
                return null;
              }));
    }
    int injectedFiles = 0;
    for (Future<?> f : injFutures) {
      try {
        f.get();
        injectedFiles++;
      } catch (ExecutionException ee) {
        Throwable cause = ee.getCause();
        String msg =
            (cause == null)
                ? ee.toString()
                : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        System.err.println("Injection error: " + msg);
      }
    }
    injPool.shutdown();
    System.out.println(">>> Injection done. Updated MAIN files: " + injectedFiles);

    // --- Phase 3: compile and run ---

    final Path runLog;

    if (execMode == ExecMode.EXTERNAL_PROJECT) {
      // Run from PROJECT ROOT so ./gradlew works
      runLog = workProjectRoot.resolve("daikonpp-run.log");

      final Path resolvedScript =
          runnerScriptPath.isAbsolute()
              ? runnerScriptPath.toAbsolutePath().normalize()
              : workProjectRoot.resolve(runnerScriptPath).normalize();

      System.out.println("[DP] External-project mode enabled");
      System.out.println("[DP] Working project root: " + workProjectRoot);
      System.out.println("[DP] Instrumented main src: " + mainSrcRoot);
      System.out.println("[DP] Runner script: " + resolvedScript);

      // 🔥 NEW: run invariant auto-filter BEFORE Gradle
      final Path classesDir = workProjectRoot.resolve(".daikonpp-classes");

      runAutoFilterCompile(
          workProjectRoot,
          mainSrcRoot,
          userProjectRoot.resolve(relMainSrc),
          classesDir,
          BASE_CFG.externalCompileClasspath(),
          10,
          externalMainCompileScript);

      System.out.println(">>> Invariant auto-filter finished (external-project mode)");
      System.out.println(">>> Running Tests!");

      // Now run the real external test script (with timeout-recovery loop)
      final String fullRunCp = "";
      final int maxRunRetries = BASE_CFG.maxRunRetries();
      long currentTimeoutMinutes = JavaRunner.EXTERNAL_RUN_TIMEOUT_MINUTES;
      for (int runAttempt = 0; runAttempt <= maxRunRetries; runAttempt++) {
        boolean timedOut =
            JavaRunner.runExternalScript(
                resolvedScript, workProjectRoot, fullRunCp, runLog, currentTimeoutMinutes);
        if (!timedOut) break;

        System.err.println(
            "[DP] Run timed out (attempt "
                + (runAttempt + 1)
                + "/"
                + (maxRunRetries + 1)
                + ") after "
                + currentTimeoutMinutes
                + " min");

        // Double the timeout for the next attempt
        currentTimeoutMinutes *= 2;
        System.out.println("[DP] Next attempt timeout: " + currentTimeoutMinutes + " min");

        if (runAttempt >= maxRunRetries) {
          System.err.println("[DP] Max run retries exceeded. Proceeding with partial log.");
          break;
        }

        Optional<UUID> stuckId = LogParser.readLastExecutedId(runLog);
        if (stuckId.isEmpty()) {
          System.err.println(
              "[DP] No INV_EXD in log; cannot identify stuck invariant. Giving up.");
          break;
        }

        System.out.println("[DP] Removing stuck invariant region: " + stuckId.get());
        boolean removed = JavaRunner.removeRegionById(mainSrcRoot, stuckId.get());
        if (!removed) {
          System.err.println(
              "[DP] Could not find region for " + stuckId.get() + ". Giving up.");
          break;
        }
      }
    } else {
      // Native mode (UNCHANGED)
      final Path classesDir = mainSrcRoot.resolve("daikonpp-classes");
      final String selfCp = System.getProperty("java.class.path");

      final String fullRunCp;
      if (splitMode) {
        runAutoFilterCompile(
            workProjectRoot,
            mainSrcRoot,
            userMainSrcRoot,
            classesDir,
            mainClasspath,
            10,
            externalMainCompileScript);

        System.out.println(">>> Main compilation phase finished successfully");

        final String testCompileCp =
            JavaRunner.joinCp(classesDir.toString(), mainClasspath, testClasspath);
        runAutoFilterCompile(
            workProjectRoot,
            testSrcRoot,
            userTestSrcRoot,
            classesDir,
            testCompileCp,
            0,
            externalTestCompileScript);

        System.out.println(">>> Test compilation phase finished successfully");

        fullRunCp = JavaRunner.joinCp(selfCp, classesDir.toString(), mainClasspath, testClasspath);
      } else {
        runAutoFilterCompile(
            workProjectRoot,
            mainSrcRoot,
            userMainSrcRoot,
            classesDir,
            mainClasspath,
            10,
            externalMainCompileScript);

        System.out.println(">>> Compilation phase finished successfully");
        fullRunCp = JavaRunner.joinCp(selfCp, classesDir.toString(), mainClasspath);
      }

      runLog = mainSrcRoot.resolve("daikonpp-run.log");
      JavaRunner.run(entryClass, fullRunCp, programArgs, runLog);
    }

    if (execMode == ExecMode.NATIVE) {
      long exitLines = 0;
      try {
        exitLines = Files.lines(runLog).filter(s -> s.contains("\"phase\":\"EXIT\"")).count();
      } catch (IOException ioe) {
        // ignore
      }
      System.out.println(">>> Run log — EXIT events: " + exitLines);
    }

    // --- Phase 4: parse run log and generate the results (same as before) ---

    final Set<UUID> falsified = LogParser.readFalsifiedIds(runLog);
    final Set<UUID> executed = LogParser.readExecutedIds(runLog);
    final Set<UUID> nonCompiled = LogParser.readNonCompiledIds(mainSrcRoot);

    final Map<UUID, edu.njit.jerse.daikonplusplus.App.RecordLite> all =
        parseRegistryLite(cfg.registryPath());

    final Set<UUID> compiledIds = new HashSet<>(all.keySet());
    compiledIds.removeAll(nonCompiled);

    Map<UUID, InvariantRegistry.Outcome> outcomes = new HashMap<>();
    for (var e : all.entrySet()) {
      UUID id = e.getKey();
      boolean compiled = compiledIds.contains(id);
      boolean exec = executed.contains(id);

      InvariantRegistry.Verdict verdict;
      if (nonCompiled.contains(id)) {
        verdict = InvariantRegistry.Verdict.FAILED_TO_COMPILE;
      } else if (exec && falsified.contains(id)) {
        verdict = InvariantRegistry.Verdict.FALSIFIED;
      } else if (exec) {
        verdict = InvariantRegistry.Verdict.HELD;
      } else if (compiled) {
        verdict = InvariantRegistry.Verdict.NEVER_EXECUTED;
      } else {
        verdict = InvariantRegistry.Verdict.PROPOSED;
      }

      outcomes.put(id, new InvariantRegistry.Outcome(compiled, exec, verdict));
    }

    InvariantRegistry.writeOutcomes(cfg.outcomesPath(), outcomes);
    System.out.println(">>> Outcomes: " + cfg.outcomesPath().toAbsolutePath());

    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> heldByMethod = new TreeMap<>();
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> falsByMethod = new TreeMap<>();
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> neverExecByMethod =
        new TreeMap<>();
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> execByMethod = new TreeMap<>();
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> compiledByMethod =
        new TreeMap<>();
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> failedCompileByMethod =
        new TreeMap<>();

    for (var r : all.values()) {
      boolean isCompiled = compiledIds.contains(r.id);
      boolean wasExecuted = executed.contains(r.id);
      boolean wasFalsified = falsified.contains(r.id);

      if (isCompiled) {
        compiledByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      } else if (nonCompiled.contains(r.id)) {
        failedCompileByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      }

      if (wasExecuted) {
        execByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      }

      if (wasExecuted && !wasFalsified) {
        heldByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      } else if (wasFalsified) {
        falsByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      } else if (isCompiled && !wasExecuted) {
        neverExecByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      }
    }

    int heldCount = heldByMethod.values().stream().mapToInt(List::size).sum();
    int falsCount = falsByMethod.values().stream().mapToInt(List::size).sum();
    int neverExecCount = neverExecByMethod.values().stream().mapToInt(List::size).sum();
    int compiledCount = compiledByMethod.values().stream().mapToInt(List::size).sum();
    int executedCount = execByMethod.values().stream().mapToInt(List::size).sum();

    System.out.println(
        ">>> Totals: "
            + "all="
            + all.size()
            + " compiled="
            + compiledCount
            + " non-compiled="
            + nonCompiled.size()
            + " executed="
            + executedCount
            + " falsified="
            + falsCount
            + " observed-held="
            + heldCount
            + " never-executed="
            + neverExecCount);

    System.out.println(">>> OBSERVED-HELD invariants by method (ENTRY & EXIT):");
    for (var e : heldByMethod.entrySet()) {
      System.out.println("  - " + e.getKey());
      for (var r : e.getValue()) {
        System.out.println("      [" + r.kind + "] " + r.id + " :: " + r.expr);
      }
    }

    System.out.println(">>> FALSIFIED invariants by method (ENTRY & EXIT):");
    for (var e : falsByMethod.entrySet()) {
      System.out.println("  - " + e.getKey());
      for (var r : e.getValue()) {
        System.out.println("      [" + r.kind + "] " + r.id + " :: " + r.expr);
      }
    }

    System.out.println(">>> FAILED-TO-COMPILE invariants by method:");
    for (var e : failedCompileByMethod.entrySet()) {
      System.out.println("  - " + e.getKey());
      for (var r : e.getValue()) {
        System.out.println("      [" + r.kind + "] " + r.id + " :: " + r.expr);
      }
    }

    System.out.println(">>> NEVER-EXECUTED invariants by method (compiled but never observed):");
    for (var e : neverExecByMethod.entrySet()) {
      System.out.println("  - " + e.getKey());
      for (var r : e.getValue()) {
        System.out.println("      [" + r.kind + "] " + r.id + " :: " + r.expr);
      }
    }

    Set<UUID> heldExecCompiled = new HashSet<>(compiledIds);
    heldExecCompiled.retainAll(executed);
    heldExecCompiled.removeAll(falsified);

    java.util.function.Function<Set<UUID>, String> idsToLine =
        s ->
            s.stream().map(UUID::toString).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");

    System.out.println(">>> SUMMARY (IDs)");
    System.out.println("  COMPILED IDs: " + idsToLine.apply(compiledIds));
    System.out.println("  EXECUTED IDs: " + idsToLine.apply(executed));
    System.out.println("  HELD∩EXECUTED∩COMPILED IDs: " + idsToLine.apply(heldExecCompiled));

    System.out.println(">>> Registry: " + cfg.registryPath().toAbsolutePath());
    System.out.println(">>> Outcomes: " + cfg.outcomesPath().toAbsolutePath());
    System.out.println(">>> Run log: " + runLog.toAbsolutePath());

    if (execMode == ExecMode.EXTERNAL_PROJECT && BASE_CFG.enableTestFilter()) {
      final Path resolvedScript =
          runnerScriptPath.isAbsolute()
              ? runnerScriptPath.toAbsolutePath().normalize()
              : workProjectRoot.resolve(runnerScriptPath).normalize();

      TestInvariantFilter.Result filterResult =
          TestInvariantFilter.run(
              workProjectRoot,
              mainSrcRoot,
              cfg.registryPath(),
              runLog,
              resolvedScript,
              BASE_CFG.testFilterMethodBatchSize());

      System.out.println(">>> TEST-FILTER REMOVED IDS:");
      for (UUID id : filterResult.removedIds.stream().sorted().toList()) {
        System.out.println("  " + id);
      }

      System.out.println(">>> TEST-FILTER REMOVED METHOD BATCHES:");
      for (String m : filterResult.removedMethodBatches) {
        System.out.println("  " + m);
      }

      System.out.println(">>> TEST-FILTER FINAL PROJECT: " + filterResult.finalProjectRoot);
      System.out.println(">>> TEST-FILTER FINAL LOG: " + filterResult.finalRunLog);

      Set<UUID> filteredFalsified = LogParser.readFalsifiedIds(filterResult.finalRunLog);
      Set<UUID> filteredExecuted = LogParser.readExecutedIds(filterResult.finalRunLog);
      Set<UUID> filteredNonCompiled = LogParser.readNonCompiledIds(filterResult.finalMainSrcRoot);

      System.out.println(">>> TEST-FILTER FINAL TOTALS:");
      System.out.println("  executed=" + filteredExecuted.size());
      System.out.println("  falsified=" + filteredFalsified.size());
      System.out.println("  non-compiled=" + filteredNonCompiled.size());
      System.out.println("  removed-by-test-filter=" + filterResult.removedIds.size());
    }

    if (!BASE_CFG.keepWork()) {
      try {
        if (execMode == ExecMode.EXTERNAL_PROJECT) {
          deleteTree(workProjectRoot);
          System.out.println(">>> Cleaned working project copy");
        } else {
          deleteTree(mainSrcRoot);
          if (splitMode && !testSrcRoot.equals(mainSrcRoot)) {
            deleteTree(testSrcRoot);
          }
          System.out.println(">>> Cleaned working copy(ies)");
        }
      } catch (IOException ioe) {
        System.err.println("Warning: failed to delete working copy: " + ioe.getMessage());
      }
    } else {
      System.out.println(">>> Keeping working copy(ies) (DP_KEEP_WORK=1):");
      if (execMode == ExecMode.EXTERNAL_PROJECT) {
        System.out.println("    project: " + workProjectRoot);
        System.out.println("    main: " + mainSrcRoot);
        System.out.println("    test: " + testSrcRoot);
      } else {
        System.out.println("    main: " + mainSrcRoot);
        if (splitMode) {
          System.out.println("    test: " + testSrcRoot);
        }
      }
    }
  }

  // ----- helpers -----

  /**
   * Processes a single program point by generating, deduplicating, and registering candidate
   * invariants.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Extracts in-scope variables and optional contextual information (e.g., method body,
   *       Javadoc, type documentation) based on configuration.
   *   <li>Invokes the LLM to propose candidate invariants.
   *   <li>Performs <b>run-level deduplication</b> to avoid duplicate expressions within the same
   *       run.
   *   <li>Assigns a fresh UUID to each invariant and appends it to the registry if not already
   *       present.
   * </ol>
   *
   * <p>Failures during processing are caught and result in no invariants for the given point.
   *
   * @param point the program point (e.g., method ENTRY or EXIT)
   * @param srcRoot root of the source tree used for context extraction
   * @param llm the invariant generator backed by an LLM
   * @param registry the global registry for storing invariant records
   * @return a list of newly generated invariant records (may be empty)
   */
  private static List<InvariantRecord> processPoint(
      ProgramPoint point, Path srcRoot, LlmInvariantGenerator llm, InvariantRegistry registry) {

    try {
      Map<String, String> inScope = ContextUtils.extractScope(point, srcRoot);

      Set<ContextKind> enabled = BASE_CFG.enabledContexts();

      String methodBody =
          enabled.contains(ContextKind.METHOD_BODY)
              ? ContextUtils.extractMethodBodyRaw(point, srcRoot).orElse("")
              : "";

      String methodJavadoc =
          enabled.contains(ContextKind.METHOD_JAVADOC)
              ? ContextUtils.extractMethodJavadoc(point, srcRoot).orElse("")
              : "";

      String classDoc =
          enabled.contains(ContextKind.CLASS_DOC)
              ? ContextUtils.extractClassDocumentation(point, srcRoot).orElse("")
              : "";

      String typeDoc =
          enabled.contains(ContextKind.TYPE_DOC)
              ? ContextUtils.extractTypeDocumentation(point, srcRoot).orElse("")
              : "";

      String callSite =
          enabled.contains(ContextKind.CALL_SITE)
              ? ContextUtils.extractCallSiteContext(point, srcRoot).orElse("")
              : "";

      String ioExamples =
          enabled.contains(ContextKind.IO_EXAMPLES)
              ? ContextUtils.extractIOExamples(point, srcRoot).orElse("")
              : "";

      String calleeDoc =
          enabled.contains(ContextKind.CALLEE_DOC)
              ? ContextUtils.extractCalleeDocumentation(point, srcRoot).orElse("")
              : "";

      List<InvariantSpec> specs =
          llm.proposeInvariants(
              point,
              inScope,
              methodBody,
              methodJavadoc,
              classDoc,
              typeDoc,
              callSite,
              ioExamples,
              calleeDoc);

      if (specs.isEmpty()) return List.of();

      List<InvariantRecord> out = new ArrayList<>(specs.size());
      java.time.Instant now = java.time.Instant.now();
      String fileRel = point.elementId().filePath();

      for (InvariantSpec spec : specs) {
        // run-level dedup
        String key = keyFor(point, spec.expression());
        if (!RUN_DEDUP.add(key)) continue;

        InvariantRecord rec =
            new InvariantRecord(java.util.UUID.randomUUID(), spec, point, fileRel, now);

        // registry-level dedup
        registry.appendIfNew(rec);
        out.add(rec);
      }

      return out;

    } catch (Exception e) {
      System.err.println("processPoint error for " + point.elementId() + ": " + e.getMessage());
      return List.of();
    }
  }

  /**
   * Parses the invariant registry file into a lightweight in-memory representation.
   *
   * <p>This method avoids full object deserialization and instead extracts only essential fields
   * (ID, expression, kind, and element) for reporting purposes.
   *
   * @param registryJsonl path to the registry file (JSONL format)
   * @return map from invariant UUID to lightweight record
   */
  private static Map<UUID, edu.njit.jerse.daikonplusplus.App.RecordLite> parseRegistryLite(
      Path registryJsonl) {
    Map<UUID, edu.njit.jerse.daikonplusplus.App.RecordLite> out = new HashMap<>();
    if (!Files.exists(registryJsonl)) return out;
    try {
      for (String line : Files.readAllLines(registryJsonl)) {
        if (line.isBlank()) continue;
        UUID id = extract(line, "\"id\":\"", "\"").map(UUID::fromString).orElse(null);
        String expr = extract(line, "\"expr\":\"", "\"").orElse(null);
        String kind = extract(line, "\"kind\":\"", "\"").orElse("METHOD_ENTRY");
        String element = extract(line, "\"element\":\"", "\"").orElse("<?>");
        if (id != null && expr != null)
          out.put(id, new edu.njit.jerse.daikonplusplus.App.RecordLite(id, expr, kind, element));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed reading registry: " + e.getMessage(), e);
    }
    return out;
  }

  private static Optional<String> extract(String s, String start, String end) {
    int i = s.indexOf(start);
    if (i < 0) return Optional.empty();
    int j = s.indexOf(end, i + start.length());
    if (j < 0) return Optional.empty();
    return Optional.of(
        s.substring(i + start.length(), j).replace("\\\"", "\"").replace("\\\\", "\\"));
  }

  private static final class RecordLite {
    final UUID id;
    final String expr;
    final String kind;
    final String element;

    RecordLite(UUID id, String expr, String kind, String element) {
      this.id = id;
      this.expr = expr;
      this.kind = kind;
      this.element = element;
    }
  }

  /**
   * Creates an isolated working copy of a source tree for instrumentation and execution.
   *
   * <p>The copy is placed under a timestamped directory inside the configured working directory.
   * All transformations (injection, compilation, filtering) are applied to this copy to ensure that
   * the original source tree remains unchanged.
   *
   * @param userSrcRoot the original source or project root
   * @return path to the newly created working copy
   * @throws IOException if copying fails
   */
  private static Path prepareWorkingCopy(Path userSrcRoot) throws IOException {
    String base = BASE_CFG.workDir();
    Path baseDir = Path.of(base).toAbsolutePath().normalize();
    Files.createDirectories(baseDir);

    String stamp =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now());

    Path workRoot = baseDir.resolve("project-" + stamp);

    copyTree(userSrcRoot, workRoot);
    System.out.println(">>> Working project copy created at: " + workRoot);
    return workRoot;
  }

  /**
   * Recursively copies a directory tree from a source location to a destination.
   *
   * <p>This method preserves file attributes and prevents accidental recursive self-copy (i.e.,
   * copying a directory into itself or vice versa).
   *
   * @param from source directory
   * @param to destination directory
   * @throws IOException if an I/O error occurs during copying
   * @throws IllegalStateException if the source and destination overlap
   */
  private static void copyTree(Path from, Path to) throws IOException {

    Path src = from.toAbsolutePath().normalize();
    Path dst = to.toAbsolutePath().normalize();

    // Prevent recursive self-copy
    if (dst.startsWith(src) || src.startsWith(dst)) {
      throw new IllegalStateException(
          "Refusing to copy overlapping trees:\n  from=" + src + "\n  to=" + dst);
    }
    Files.createDirectories(to);
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path target = to.resolve(from.relativize(dir).toString());
            Files.createDirectories(target);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path target = to.resolve(from.relativize(file).toString());
            Files.copy(
                file,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Recursively deletes a directory tree.
   *
   * <p>This method is used to clean up working copies after execution unless explicitly disabled
   * via configuration.
   *
   * @param root root of the directory tree to delete
   * @throws IOException if deletion fails
   */
  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) return;
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Compiles instrumented code with automatic invariant filtering.
   *
   * <p>This method ensures that only compilable invariants remain in the code by iteratively
   * removing invariants that cause compilation failures.
   *
   * <p>Two modes are supported:
   *
   * <ul>
   *   <li><b>External compilation:</b> Uses a user-provided script (e.g., Gradle build).
   *   <li><b>Native compilation:</b> Uses an internal {@code javac}-based compilation pipeline.
   * </ul>
   *
   * <p>The process may run for multiple passes until compilation succeeds or a maximum number of
   * passes is reached.
   *
   * @param workProjectRoot root of the working project copy
   * @param srcRoot instrumented source root
   * @param userSrcRoot original source root (used for reference)
   * @param classesDir output directory for compiled classes (native mode)
   * @param classpath classpath used for compilation
   * @param maxPasses maximum number of filtering passes
   * @param externalCompileScript optional external compile script (null for native mode)
   * @throws Exception if compilation fails irrecoverably
   */
  private static void runAutoFilterCompile(
      Path workProjectRoot,
      Path srcRoot,
      Path userSrcRoot,
      Path classesDir,
      String classpath,
      int maxPasses,
      @org.checkerframework.checker.nullness.qual.Nullable Path externalCompileScript)
      throws Exception {

    if (externalCompileScript != null) {
      // User-provided compile script IS the compiler
      ExternalCompileRunner.compileWithAutoFilter(
          workProjectRoot, srcRoot, userSrcRoot, externalCompileScript, maxPasses);
    } else {
      // Native javac-based autofilter
      JavaRunner.compileWithAutoFilter(srcRoot, userSrcRoot, classesDir, classpath, maxPasses);
    }
  }

  /**
   * Determines whether a source file should be included based on configured scan filters.
   *
   * <p>Supports both:
   *
   * <ul>
   *   <li>Path-based filters (e.g., {@code com/example/utils})
   *   <li>Package-style filters (e.g., {@code com.example.utils})
   * </ul>
   *
   * @param filePath relative path of the source file
   * @param includes set of include filters
   * @return true if the file matches any filter; false otherwise
   */
  private static boolean isIncludedByScanFilter(String filePath, Set<String> includes) {
    String normalizedFile = filePath.replace("\\", "/");

    for (String rawInclude : includes) {
      String include = rawInclude.replace("\\", "/").trim();
      if (include.isEmpty()) continue;

      // Supports path-style filters:
      //   com/badlogic/gdx/utils
      //   src/com/badlogic/gdx/utils
      if (normalizedFile.contains(include)) {
        return true;
      }

      // Supports package-style filters:
      //   com.badlogic.gdx.utils
      String packageAsPath = include.replace(".", "/");
      if (normalizedFile.contains(packageAsPath)) {
        return true;
      }
    }

    return false;
  }
}
