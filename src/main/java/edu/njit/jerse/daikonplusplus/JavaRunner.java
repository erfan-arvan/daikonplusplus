package edu.njit.jerse.daikonplusplus;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil;
import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil.JError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility class for compiling and executing instrumented Java code in Daikon++.
 *
 * <p>Supports:
 *
 * <ul>
 *   <li>Running Java programs and capturing their output and invariant events
 *   <li>Compiling instrumented code with automatic removal of invariants that cause compilation
 *       errors
 * </ul>
 *
 * <p>Invariant removal is marker-based and operates either by disabling a specific invariant region
 * or restoring the original file if needed.
 */
public final class JavaRunner {

  // Old style (oneline guards)
  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";

  // New style (block guards)
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  static final long EXTERNAL_RUN_TIMEOUT_MINUTES = 60;

  /** Outcome of a {@link #runExternalScript} call. */
  public enum RunResult {
    /** Process finished within the timeout window with no stale kill. */
    NORMAL,
    /**
     * Stale-invariant detector fired: no progress for {@code staleCheckMinutes}, process killed.
     */
    STALE_KILLED,
    /** Hard wall-clock timeout elapsed before the process finished, process killed. */
    HARD_TIMEOUT
  }

  private JavaRunner() {}

  /**
   * Runs a Java program in a separate JVM and appends its output to a log file.
   *
   * @param mainClass fully qualified name of the main class to execute
   * @param classpath classpath used for execution
   * @param args arguments passed to the program
   * @param logFile file where output is written
   * @throws Exception if execution fails
   */
  public static void run(String mainClass, String classpath, List<String> args, Path logFile)
      throws Exception {
    run(mainClass, classpath, args, logFile, null);
  }

  public static void run(
      String mainClass,
      String classpath,
      List<String> args,
      Path logFile,
      @org.checkerframework.checker.nullness.qual.Nullable Path disabledFile)
      throws Exception {

    Files.createDirectories(Optional.ofNullable(logFile.getParent()).orElse(Path.of(".")));

    List<String> cmd = new ArrayList<>();
    cmd.add(tool("java"));
    cmd.add("-Dfile.encoding=UTF-8");
    cmd.add("-Xshare:off");

    Path logDir = Optional.ofNullable(logFile.getParent()).orElse(Path.of("."));
    cmd.add("-DDP_INV_DIR=" + logDir.resolve(".daikonpp-events").toAbsolutePath());

    if (disabledFile != null && Files.exists(disabledFile)) {
      cmd.add("-DDP_DISABLED_FILE=" + disabledFile.toAbsolutePath());
    }

    cmd.add("-cp");
    cmd.add(classpath);
    cmd.add(mainClass);
    cmd.addAll(args);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);

    Process p = pb.start();

    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MINUTES.toNanos(EXTERNAL_RUN_TIMEOUT_MINUTES);

    try (BufferedReader r =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w =
            Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

      String line;
      while (true) {
        while (r.ready() && (line = r.readLine()) != null) {
          w.write(line);
          w.newLine();
          w.flush();
        }

        if (!p.isAlive()) break;

        if (System.nanoTime() > deadline) {
          w.write("[DP] Runner TIMED OUT");
          w.newLine();
          w.flush();
          p.destroyForcibly();
          break;
        }
        Thread.sleep(100);
      }
    }

    int code = p.waitFor();
    if (code != 0) {
      Files.writeString(
          logFile, "\n[DP] Child JVM exit code: " + code + "\n", StandardOpenOption.APPEND);
    }

    appendDpEvents(logDir.resolve(".daikonpp-events"), logFile);
  }

  /**
   * Compiles Java sources and removes invariants that cause compilation errors.
   *
   * <p>If compilation fails, the method parses compiler errors and either:
   *
   * <ul>
   *   <li>Disables the invariant region causing the error
   *   <li>Restores the original file if the error cannot be resolved locally
   * </ul>
   *
   * <p>The process repeats until compilation succeeds or no further progress is possible.
   *
   * @param workSrcRoot root of the instrumented source tree
   * @param originalSrcRoot root of the original source tree
   * @param classesDir output directory for compiled classes
   * @param classpath classpath for compilation
   * @param maxModifyPasses maximum number of passes that attempt invariant removal
   * @throws Exception if compilation ultimately fails
   */
  public static void compileWithAutoFilter(
      Path workSrcRoot,
      Path originalSrcRoot,
      Path classesDir,
      String classpath,
      int maxModifyPasses)
      throws Exception {

    Files.createDirectories(classesDir);

    System.out.println("[DP] compileWithAutoFilter START");
    System.out.println("[DP] workSrcRoot = " + workSrcRoot.toAbsolutePath());
    System.out.println("[DP] originalSrcRoot = " + originalSrcRoot.toAbsolutePath());

    List<Path> sources = new ArrayList<>();
    try (var walk = Files.walk(workSrcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
    }

    System.out.println("[DP] Found Java sources: " + sources.size());

    if (sources.isEmpty()) {
      throw new RuntimeException("No Java sources under " + workSrcRoot);
    }

    Path argFile = classesDir.resolve("dp_sources.txt");
    try (PrintWriter pw =
        new PrintWriter(Files.newBufferedWriter(argFile, StandardCharsets.UTF_8))) {
      for (Path s : sources) {
        pw.println(s.toAbsolutePath());
      }
    }

    List<String> base = new ArrayList<>();
    base.add(tool("javac"));
    base.add("-encoding");
    base.add("UTF-8");
    base.add("-g");
    base.add("-proc:none");
    base.add("-cp");
    base.add(classpath);
    base.add("-d");
    base.add(classesDir.toString());
    base.add("@" + argFile);

    Path outLog = classesDir.resolve("dp-javac.out");
    Path errLog = classesDir.resolve("dp-javac.err");

    int pass = 1;
    int maxTotalPasses = maxModifyPasses + 20;

    while (true) {

      System.out.println("\n[DP] ===== PASS " + pass + " =====");

      boolean inModifyPhase = pass <= maxModifyPasses;
      System.out.println("[DP] inModifyPhase = " + inModifyPhase);

      int code = runProcess(base, workSrcRoot, outLog, errLog);
      System.out.println("[DP] javac exit code = " + code);

      if (code == 0) {
        System.out.println("[DP] Compilation SUCCESS");
        return;
      }

      String err = Files.exists(errLog) ? Files.readString(errLog) : "";

      System.out.println("[DP] --- RAW STDERR (first 1000 chars) ---");
      System.out.println(err.length() > 1000 ? err.substring(0, 1000) + "\n...[truncated]" : err);
      System.out.println("[DP] -------------------------------------");

      List<JError> errors = InvariantAutoFilterUtil.parseErrors(err);

      System.out.println("[DP] Parsed errors count = " + errors.size());
      for (JError e : errors) {
        System.out.println("[DP] ERROR → " + e.file + ":" + e.line);
      }

      int touched = 0;

      Set<String> seen = new HashSet<>();

      for (JError je : errors) {

        String key = je.file + ":" + je.line;
        if (!seen.add(key)) continue;

        Path file = Path.of(je.file).toAbsolutePath().normalize();

        System.out.println("[DP] Processing → " + file + ":" + je.line);

        if (inModifyPhase) {
          int removed = removeInvariantRegion(file, je.line);
          System.out.println("[DP]   removeInvariantRegion → " + removed);

          if (removed > 0) {
            touched += removed;
            continue;
          }
        }

        int restored = restoreOriginalFile(file, workSrcRoot, originalSrcRoot);
        System.out.println("[DP]   restoreOriginalFile → " + restored);

        touched += restored;
      }

      System.out.println("[DP] touched = " + touched);

      if (touched == 0) {
        System.out.println("[DP] ❌ NO PROGRESS THIS PASS");
        throw new RuntimeException("javac failed with no progress:\n" + firstErrorMsg(errors));
      }

      if (pass >= maxTotalPasses) {
        throw new RuntimeException("javac still failing after " + pass + " passes");
      }

      pass++;
    }
  }

  /**
   * Returns a string representation of the first compilation error.
   *
   * @param list list of parsed compilation errors
   * @return formatted message for the first error, or "<none>" if empty
   */
  private static String firstErrorMsg(List<JError> list) {
    return list.isEmpty()
        ? "<none>"
        : list.get(0).file + ":" + list.get(0).line + " — " + list.get(0).msg;
  }

  /**
   * Disables the invariant region around a given line number by commenting it out.
   *
   * @param file source file containing the invariant
   * @param lineno line number where the error occurred (1-based)
   * @return 1 if a region was disabled, 0 otherwise
   */
  static int removeInvariantRegion(Path file, int lineno) {
    try {
      if (!Files.isRegularFile(file)) return 0;

      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      int begin = -1, end = -1;

      int idx = Math.max(0, Math.min(lineno - 1, lines.size() - 1));

      for (int i = idx; i >= 0; i--) {
        if (lines.get(i).contains(ONELINE_BEGIN) || lines.get(i).contains(BLOCK_BEGIN)) {
          begin = i;
          break;
        }
      }
      for (int i = idx; i < lines.size(); i++) {
        if (lines.get(i).contains(ONELINE_END) || lines.get(i).contains(BLOCK_END)) {
          end = i;
          break;
        }
      }

      if (begin < 0 || end < 0) return 0;

      for (int i = begin; i <= end; i++) {
        if (!lines.get(i).trim().startsWith("// [DP] disabled")) {
          lines.set(i, "// [DP] disabled invariant :: " + lines.get(i));
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
      return 1;

    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Restores a file in the working directory from the original source tree.
   *
   * @param brokenFile file that failed compilation
   * @param workSrcRoot root of the working source tree
   * @param originalSrcRoot root of the original source tree
   * @return 1 if the file was restored, 0 otherwise
   */
  private static int restoreOriginalFile(Path brokenFile, Path workSrcRoot, Path originalSrcRoot) {

    try {
      Path workRoot = workSrcRoot.toAbsolutePath().normalize();
      Path broken = brokenFile.toAbsolutePath().normalize();

      if (!broken.startsWith(workRoot)) return 0;

      Path rel = workRoot.relativize(broken);
      Path original = originalSrcRoot.resolve(rel);

      if (!Files.isRegularFile(original)) return 0;

      Path parent = broken.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(
          original,
          broken,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
      return 1;

    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Executes a process and waits for it to complete.
   *
   * @param cmd command and arguments to execute
   * @param wd working directory for the process
   * @param out file to which standard output is redirected
   * @param err file to which standard error is redirected
   * @return exit code of the process
   * @throws Exception if process execution fails
   */
  private static int runProcess(List<String> cmd, Path wd, Path out, Path err) throws Exception {

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(wd.toFile());
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    return pb.start().waitFor();
  }

  /**
   * Resolves the path to a Java tool (e.g., {@code java}, {@code javac}).
   *
   * <p>If {@code JAVA_HOME} is set, the tool is resolved from its {@code bin} directory. Otherwise,
   * the tool name is returned as-is.
   *
   * @param base base name of the tool (e.g., "java", "javac")
   * @return resolved executable path
   */
  private static String tool(String base) {
    String ext = isWin() ? ".exe" : "";
    String home = System.getenv("JAVA_HOME");
    if (home != null) {
      Path p = Path.of(home, "bin", base + ext);
      if (Files.isRegularFile(p)) return p.toString();
    }
    return base + ext;
  }

  /**
   * Checks whether the current operating system is Windows.
   *
   * @return true if running on Windows, false otherwise
   */
  private static boolean isWin() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  /**
   * Joins classpath entries using the platform-specific separator.
   *
   * @param parts classpath entries
   * @return combined classpath string
   */
  public static String joinCp(String... parts) {
    String sep = System.getProperty("path.separator");
    return String.join(sep, Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
  }

  /**
   * Appends invariant event files from a directory into the main run log.
   *
   * @param invDir directory containing event files
   * @param runLog log file to append to
   */
  private static void appendDpEvents(Path invDir, Path runLog) {
    try {
      if (!Files.isDirectory(invDir)) return;

      List<Path> files = new ArrayList<>();
      try (var s = Files.list(invDir)) {
        s.filter(
                p -> {
                  Path name = p.getFileName();
                  return name != null && name.toString().startsWith("dp-events-");
                })
            .sorted()
            .forEach(files::add);
      }

      for (Path f : files) {
        Files.writeString(
            runLog,
            Files.readString(f, StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * Executes an external script (e.g., build or test runner) and appends its output to a log file.
   *
   * @param script executable script to run
   * @param workDir working directory for the script
   * @param fullRunCp classpath exposed to the script (may be empty)
   * @param runLog file where output is written
   * @param timeoutMinutes wall-clock minutes to wait before killing the process
   * @param staleCheckMinutes interval in minutes between stale-invariant checks (0 = disabled)
   * @return {@link RunResult} indicating how the run ended
   * @throws IOException if the script cannot be executed
   * @throws InterruptedException if execution is interrupted
   */
  public static RunResult runExternalScript(
      Path script,
      Path workDir,
      String fullRunCp,
      Path runLog,
      long timeoutMinutes,
      long staleCheckMinutes)
      throws IOException, InterruptedException {
    return runExternalScript(
        script, workDir, fullRunCp, runLog, timeoutMinutes, staleCheckMinutes, null);
  }

  public static RunResult runExternalScript(
      Path script,
      Path workDir,
      String fullRunCp,
      Path runLog,
      long timeoutMinutes,
      long staleCheckMinutes,
      @Nullable Path disabledFile)
      throws IOException, InterruptedException {

    if (!Files.isRegularFile(script)) {
      throw new IllegalArgumentException("[DP] External runner script not found: " + script);
    }

    // Ensure the script is executable — some HPC filesystems (GPFS/Lustre) strip the execute
    // bit on copy even when COPY_ATTRIBUTES is used; chmod +x as a silent fallback.
    if (!Files.isExecutable(script)) {
      try {
        script.toFile().setExecutable(true, false);
        System.out.println("[DP] chmod +x applied to runner script: " + script);
      } catch (SecurityException ignored) {
        // best-effort; if it still fails ProcessBuilder will throw a clear error
      }
    }

    Files.createDirectories(Optional.ofNullable(runLog.getParent()).orElse(Path.of(".")));

    System.out.println("[DP] Running script: " + script.toAbsolutePath());
    System.out.println("[DP] Working dir: " + workDir.toAbsolutePath());
    System.out.println("[DP] Log file: " + runLog.toAbsolutePath());
    // Use "bash <script>" so execution works even when the filesystem ignores execute bits.
    ProcessBuilder pb = new ProcessBuilder("bash", script.toAbsolutePath().toString());

    // Run inside the working project copy
    pb.directory(workDir.toFile());

    Map<String, String> env = pb.environment();

    if (fullRunCp != null && !fullRunCp.isBlank()) {
      env.put("DP_DAIKONPP_CLASSPATH", fullRunCp);
    }

    env.put("DP_RUN_LOG", runLog.toAbsolutePath().toString());

    Path invDir = workDir.resolve(".daikonpp-events");
    Files.createDirectories(invDir);

    env.put("DP_INV_DIR", invDir.toAbsolutePath().toString());

    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath();

    env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + jvmArgs).trim());
    env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + jvmArgs).trim());
    env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " " + jvmArgs).trim());

    if (disabledFile != null) {
      String disabledPath = disabledFile.toAbsolutePath().toString();
      String disabledArg = "-DDP_DISABLED_FILE=" + disabledPath;
      env.put("DP_DISABLED_FILE", disabledPath);
      env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + disabledArg).trim());
      env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + disabledArg).trim());
      env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " " + disabledArg).trim());
    }

    pb.redirectErrorStream(true);

    // Capture log size BEFORE starting the process so the stale detector
    // only reads INV_EXD entries written by THIS run, not previous runs.
    final long logStartOffset;
    try {
      logStartOffset = Files.exists(runLog) ? Files.size(runLog) : 0L;
    } catch (IOException e) {
      throw new IOException("Cannot determine log file size: " + e.getMessage(), e);
    }

    Process p = pb.start();

    // ---- ASYNC OUTPUT READER ----
    Thread readerThread =
        new Thread(
            () -> {
              try (BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                  BufferedWriter w =
                      Files.newBufferedWriter(
                          runLog,
                          StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE,
                          StandardOpenOption.APPEND)) {

                String line;
                while (true) {
                  if (Thread.currentThread().isInterrupted()) break;

                  if (!r.ready()) {
                    try {
                      Thread.sleep(50);
                    } catch (InterruptedException e) {
                      break;
                    }
                    continue;
                  }

                  line = r.readLine();
                  if (line == null) break;

                  w.write(line);
                  w.newLine();
                  w.flush();
                }

              } catch (IOException ignored) {
              }
            });

    readerThread.setDaemon(true);
    readerThread.start();

    // ---- STALE INVARIANT DETECTOR ----
    // Polls every 60 seconds. Kills the process when the same UUID has been the
    // last-executed invariant continuously for staleCheckMinutes. Fires after exactly
    // staleCheckMinutes of no progress (not 2x like the old two-observation design).
    AtomicBoolean staleKilled = new AtomicBoolean(false);
    Thread staleThread = null;
    if (staleCheckMinutes > 0) {
      final long pollMs = 60_000L; // poll every 60 seconds
      final long thresholdMs = staleCheckMinutes * 60_000L;
      System.out.println(
          "[DP] Stale detector started — threshold: "
              + staleCheckMinutes
              + " min, poll: 60 s, log: "
              + runLog.toAbsolutePath());
      staleThread =
          new Thread(
              () -> {
                UUID trackedId = null;
                long trackedSince = 0;
                while (!Thread.currentThread().isInterrupted() && p.isAlive()) {
                  try {
                    Thread.sleep(pollMs);
                  } catch (InterruptedException e) {
                    break;
                  }
                  if (!p.isAlive()) break;

                  UUID currentId;
                  try {
                    currentId =
                        LogParser.readLastExecutedIdFrom(runLog, logStartOffset).orElse(null);
                  } catch (Exception e) {
                    System.err.println("[DP] Stale detector: error reading log: " + e.getMessage());
                    continue; // keep polling — don't die on transient IO errors
                  }

                  long now = System.currentTimeMillis();

                  if (currentId == null) {
                    // no INV_EXD in log yet — reset and wait
                    trackedId = null;
                    continue;
                  }

                  // If the last-executed invariant was already falsified it completed
                  // normally (the INV_FAIL was written). The process is stuck on
                  // something else; reset tracking and let the hard timeout handle it.
                  try {
                    if (LogParser.readFalsifiedIds(runLog).contains(currentId)) {
                      if (!currentId.equals(trackedId)) {
                        System.out.println(
                            "[DP] Stale detector: "
                                + currentId
                                + " already falsified — skipping stale check");
                      }
                      trackedId = null;
                      continue;
                    }
                  } catch (Exception e) {
                    System.err.println(
                        "[DP] Stale detector: error reading falsified IDs: " + e.getMessage());
                  }

                  if (!currentId.equals(trackedId)) {
                    // UUID advanced — reset the clock
                    trackedId = currentId;
                    trackedSince = now;
                    continue;
                  }

                  long stuckForMs = now - trackedSince;
                  System.out.println(
                      "[DP] Stale detector: "
                          + currentId
                          + " unchanged for "
                          + (stuckForMs / 60_000)
                          + " min"
                          + " (threshold "
                          + staleCheckMinutes
                          + " min)");

                  if (stuckForMs >= thresholdMs) {
                    try {
                      Files.writeString(
                          runLog,
                          "\n[DP] Stale invariant detected ("
                              + currentId
                              + ") - no progress for "
                              + staleCheckMinutes
                              + " min, killing run\n",
                          StandardOpenOption.CREATE,
                          StandardOpenOption.APPEND);
                    } catch (IOException ignored) {
                    }
                    System.err.println(
                        "[DP] Stale invariant detected ("
                            + currentId
                            + ") after "
                            + staleCheckMinutes
                            + " min. Killing runner.");
                    staleKilled.set(true);
                    p.destroyForcibly();
                    break;
                  }
                }
                System.out.println(
                    "[DP] Stale detector thread exiting (staleKilled=" + staleKilled.get() + ")");
              });
      staleThread.setDaemon(true);
      staleThread.start();
    } else {
      System.out.println("[DP] Stale detector disabled (staleCheckMinutes=0)");
    }

    // ---- TIMEOUT CONTROL ----
    boolean finished = p.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES);

    // Stop the stale checker regardless of how the process ended
    if (staleThread != null) {
      staleThread.interrupt();
    }

    if (!finished) {
      Files.writeString(
          runLog,
          "\n[DP] External runner TIMED OUT after " + timeoutMinutes + " minutes\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);

      p.destroyForcibly();
      p.waitFor();
      readerThread.interrupt();
    } else if (staleKilled.get()) {
      // Process was killed by stale detector; it already exited, just drain
      readerThread.interrupt();
    }

    RunResult runResult =
        !finished
            ? RunResult.HARD_TIMEOUT
            : staleKilled.get() ? RunResult.STALE_KILLED : RunResult.NORMAL;

    int exit;

    if (runResult != RunResult.NORMAL) {
      // killed by timeout or stale detector → give reader a short chance to drain
      readerThread.join(20000);
      exit = -1;
    } else {
      exit = p.exitValue();
    }

    appendDpEvents(invDir, runLog);

    if (exit != 0 && runResult == RunResult.NORMAL) {
      Files.writeString(
          runLog,
          "\n[DP] External runner exited with code " + exit + "\n",
          StandardOpenOption.APPEND);
    }

    return runResult;
  }

  /**
   * Searches all {@code .java} files under {@code srcRoot} for a line containing {@code
   * INV_EXD:<stuckId>} and removes the surrounding invariant region by delegating to {@link
   * #removeInvariantRegion(Path, int)}.
   *
   * @return {@code true} if a region was found and removed
   */
  /**
   * Appends {@code stuckId} to {@code disabledFile} (one UUID per line). On the next run the child
   * JVM receives {@code -DDP_DISABLED_FILE=<path>} and {@code daikonpp.DpRuntime.DISABLED} skips
   * every invariant with that UUID — across all return paths simultaneously, without touching
   * source.
   */
  public static void disableInvariant(Path disabledFile, UUID stuckId) throws IOException {
    Path dir = disabledFile.getParent();
    if (dir != null) Files.createDirectories(dir);
    Files.writeString(
        disabledFile,
        stuckId.toString() + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    System.out.println("[DP] Disabled stuck invariant " + stuckId + " → " + disabledFile);
  }

  public static boolean removeRegionById(Path srcRoot, UUID stuckId) {
    String marker = stuckId.toString();
    try {
      List<Path> javaFiles = new ArrayList<>();
      try (var walk = Files.walk(srcRoot)) {
        walk.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
      }
      for (Path file : javaFiles) {
        if (!Files.isRegularFile(file)) continue;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          if (lines.get(i).contains(marker)) {
            int removed = removeInvariantRegion(file, i + 1);
            if (removed > 0) {
              System.out.println(
                  "[DP] Removed stuck invariant region ("
                      + stuckId
                      + ") in "
                      + file
                      + ":"
                      + (i + 1));
              return true;
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("[DP] removeRegionById failed for " + stuckId + ": " + e.getMessage());
    }
    return false;
  }
}
