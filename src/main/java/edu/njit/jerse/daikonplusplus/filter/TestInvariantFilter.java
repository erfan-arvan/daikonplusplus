package edu.njit.jerse.daikonplusplus.filter;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Test-based invariant filtering that removes invariants causing test failures.
 *
 * <p>Strategy: the initial run (whose log the caller already produced) is checked cheaply — exit
 * code first, and only if non-zero, scanned once for a recognized {@link TestFailureLogParser}
 * signature. If neither confirms a real test failure, nothing is filtered — and no project copy or
 * rerun happens at all.
 *
 * <p>If a failure is confirmed, the candidate pool of invariants to search over is seeded directly
 * from that same already-completed initial run's log (every {@code INV_EXD} entry — invariants that
 * executed at least once) — <em>not</em> by rerunning the whole suite again, since that run already
 * paid the cost of executing everything once and already recorded the result.
 *
 * <p>From there, <a href="https://www.debuggingbook.org/html/DeltaDebugger.html">delta
 * debugging</a> ({@code ddmin}) isolates a 1-minimal culprit set: candidates are disabled/enabled
 * in shrinking, targeted subsets, each checked with a real-time monitored rerun on a working copy —
 * as the runner's output streams in, each line is checked against the same failure signature, and
 * the instant the <em>same</em> failure (see {@link TestFailureLogParser#isSameFailure}) reappears,
 * the process is killed immediately rather than waiting for the rest of the trial's suite run to
 * finish. That isolated set is disabled in the final project copy.
 *
 * <p>The process operates on copies of the project to avoid mutating the original injected code and
 * uses marker-based regions to selectively disable invariants.
 */
public final class TestInvariantFilter {

  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private TestInvariantFilter() {}

  /**
   * Executes test-based filtering to identify and remove invariants that break tests.
   *
   * @param injectedProjectRoot root of the project with injected invariants
   * @param mainSrcRoot source root containing instrumented Java files
   * @param registryPath registry mapping invariant IDs to program elements
   * @param initialRunLog log from the initial execution, already produced by the caller
   * @param runnerScript external test runner script
   * @param methodBatchSize unused by the current (delta-debugging) strategy; kept for source
   *     compatibility with existing callers/config
   * @return result object describing the final filtered project and removed invariants
   * @throws Exception if execution fails
   */
  public static Result run(
      Path injectedProjectRoot,
      Path mainSrcRoot,
      Path registryPath,
      Path initialRunLog,
      Path runnerScript,
      int methodBatchSize)
      throws Exception {

    System.out.println("\n[DP-TEST-FILTER] ===== START TEST-BASED FILTERING =====");
    System.out.println("[DP-TEST-FILTER] strategy = real-time-detection + delta-debugging");

    Map<UUID, String> idToMethod = readRegistryMethods(registryPath);

    System.out.println("[DP-TEST-FILTER] Checking initial run exit status: " + initialRunLog);

    if (!exitedNonZero(initialRunLog)) {
      System.out.println(
          "[DP-TEST-FILTER] Initial run exited cleanly (exit=0) — nothing to filter "
              + "(skipping log scan and project copy)");
      System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");
      return noopResult(injectedProjectRoot, mainSrcRoot, initialRunLog, 0);
    }

    System.out.println(
        "[DP-TEST-FILTER] Initial run exited non-zero — scanning log for a recognized "
            + "test-failure signature: "
            + initialRunLog);
    String initialLogText = readIfExists(initialRunLog);
    Optional<TestFailureLogParser.FailureMatch> initialFailure =
        TestFailureLogParser.firstFailure(initialLogText);

    if (initialFailure.isEmpty()) {
      System.out.println(
          "[DP-TEST-FILTER] Initial run failed (exit!=0) but no recognized test-failure "
              + "signature was found — cannot safely attribute to an invariant, nothing to "
              + "filter (skipping project copy)");
      System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");
      return noopResult(injectedProjectRoot, mainSrcRoot, initialRunLog, 1);
    }

    TestFailureLogParser.FailureMatch target = initialFailure.get();
    System.out.println(
        "[DP-TEST-FILTER] Initial run failed [" + target.format() + "] :: " + target.line());

    System.out.println(
        "[DP-TEST-FILTER] Creating working copy from " + injectedProjectRoot + " ...");
    long copyStart = System.nanoTime();
    Path working = freshCopy(injectedProjectRoot, "test-filter-work");
    Path workingMainSrc = working.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();
    System.out.println(
        "[DP-TEST-FILTER] Working copy ready at "
            + working
            + " ("
            + (System.nanoTime() - copyStart) / 1_000_000
            + " ms)");

    Path shmDir = working.resolve(".daikonpp-test-filter-shm");
    Files.createDirectories(shmDir);

    // ---- Candidate set: seeded directly from the already-completed initial run — no rerun
    // needed. That run already executed the whole suite and its log already records every
    // invariant that ran (INV_EXD entries, flushed by DpRuntime's shutdown-hook sidecar), so
    // rerunning the suite again here just to rebuild that same information would pay for the
    // entire (potentially many-minute) run a second time for nothing. ddmin's own trials below
    // are the only reruns actually needed. ----
    List<UUID> candidates = new ArrayList<>(LogParser.readExecutedIds(initialRunLog));
    System.out.println(
        "[DP-TEST-FILTER] candidate pool size = "
            + candidates.size()
            + " (from initial run's executed-invariants log, no rerun)");

    if (candidates.isEmpty()) {
      System.out.println(
          "[DP-TEST-FILTER] No invariants executed during the initial run — cannot attribute, "
              + "stopping");
      System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");
      return new Result(
          injectedProjectRoot, working, workingMainSrc, initialRunLog, Set.of(), List.of(), 1);
    }

    BlockIndex idx = scanInvariantBlocks(workingMainSrc);
    Map<UUID, List<String>> originalLines = captureOriginalLines(idx, candidates);

    List<String> trialLog = new ArrayList<>();
    TrialCounter trialCounter = new TrialCounter();

    List<UUID> culprits =
        ddmin(
            runnerScript,
            working,
            shmDir,
            idx,
            originalLines,
            candidates,
            target,
            idToMethod,
            trialLog,
            trialCounter);

    // Final state: disable exactly the isolated culprits, restore everything else in the
    // candidate pool to its original (enabled) form, and do one confirming run.
    Set<UUID> culpritSet = new LinkedHashSet<>(culprits);
    Set<UUID> keepEnabled = new LinkedHashSet<>(candidates);
    keepEnabled.removeAll(culpritSet);
    applyConfig(idx, originalLines, candidates, keepEnabled);

    resetShmDir(shmDir);
    Path finalLog = working.resolve("daikonpp-test-filter-final.log");
    System.out.println("[DP-TEST-FILTER] Final run (culprits disabled): log -> " + finalLog);
    int finalExit = runExternalTestRunner(runnerScript, working, finalLog, shmDir);

    String finalLogText = readIfExists(finalLog);
    boolean stillFails =
        finalExit != 0
            && TestFailureLogParser.firstFailure(finalLogText)
                .map(m -> TestFailureLogParser.isSameFailure(target, m))
                .orElse(false);

    for (UUID id : culprits) {
      String method = idToMethod.getOrDefault(id, "(unknown method)");
      trialLog.add(
          "Disabled "
              + id
              + " ["
              + method
              + "] — isolated via delta debugging ("
              + trialCounter.count
              + " trial(s)) against target ["
              + target.format()
              + "] :: "
              + target.line());
    }

    System.out.println(
        "[DP-TEST-FILTER] Isolated culprit set: "
            + culprits.size()
            + " invariant(s) after "
            + trialCounter.count
            + " ddmin trial(s)");
    System.out.println(
        "[DP-TEST-FILTER] Final run: "
            + (stillFails
                ? "STILL FAILS (unexpected)"
                : finalExit == 0 ? "PASSED" : "FAILED (unrelated)")
            + " exit="
            + finalExit);
    System.out.println("[DP-TEST-FILTER] final project=" + working);
    System.out.println("[DP-TEST-FILTER] final log=" + finalLog);
    System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

    return new Result(
        injectedProjectRoot, working, workingMainSrc, finalLog, culpritSet, trialLog, finalExit);
  }

  private static Result noopResult(
      Path injectedProjectRoot, Path mainSrcRoot, Path initialRunLog, int exitCode) {
    return new Result(
        injectedProjectRoot,
        injectedProjectRoot,
        mainSrcRoot,
        initialRunLog,
        Set.of(),
        List.of(),
        exitCode);
  }

  // =====================================================================================
  // Delta debugging (ddmin)
  // =====================================================================================

  private static final class TrialCounter {
    int count = 0;
  }

  /**
   * Isolates a 1-minimal subset of {@code delta} whose presence (enabled, with everything else in
   * {@code delta} disabled) reproduces {@code target}. {@code delta} is assumed to reproduce the
   * failure when fully enabled — that's how it was built (from a run that just reproduced it).
   *
   * <p>Classic {@code ddmin}: split into {@code n} chunks; try each chunk alone; if none
   * reproduces, try each chunk's complement (i.e. removing just that chunk); if that doesn't narrow
   * things either, increase granularity (double {@code n}) and retry, until {@code n} reaches
   * {@code delta.size()} with no further progress — at which point {@code delta} is 1-minimal.
   *
   * @return the isolated minimal culprit set (in original execution order)
   */
  private static List<UUID> ddmin(
      Path runnerScript,
      Path working,
      Path shmDir,
      BlockIndex idx,
      Map<UUID, List<String>> originalLines,
      List<UUID> deltaInit,
      TestFailureLogParser.FailureMatch target,
      Map<UUID, String> idToMethod,
      List<String> trialLog,
      TrialCounter trialCounter)
      throws IOException, InterruptedException {

    List<UUID> delta = new ArrayList<>(deltaInit);
    int n = 2;

    while (delta.size() > 1) {
      n = Math.min(n, delta.size());
      // Candidates come from the initial run's executed-invariants log (a set, not an ordered
      // sequence — see the comment where `candidates` is built), so chunk order here carries no
      // proximity-to-failure meaning; it's just a deterministic split, not a search heuristic.
      List<List<UUID>> chunks = splitInto(delta, n);

      boolean reduced = false;

      for (List<UUID> chunk : chunks) {
        if (chunk.isEmpty()) continue;
        int trialNum = ++trialCounter.count;
        boolean fail =
            testConfig(
                trialNum,
                "alone",
                chunk,
                new LinkedHashSet<>(chunk),
                delta,
                runnerScript,
                working,
                shmDir,
                idx,
                originalLines,
                target,
                trialLog);
        if (fail) {
          delta = new ArrayList<>(chunk);
          n = 2;
          reduced = true;
          break;
        }
      }

      if (!reduced) {
        for (List<UUID> chunk : chunks) {
          if (chunk.isEmpty() || chunk.size() == delta.size()) continue;
          int trialNum = ++trialCounter.count;
          List<UUID> complement = new ArrayList<>(delta);
          complement.removeAll(chunk);
          boolean fail =
              testConfig(
                  trialNum,
                  "without",
                  chunk,
                  new LinkedHashSet<>(complement),
                  delta,
                  runnerScript,
                  working,
                  shmDir,
                  idx,
                  originalLines,
                  target,
                  trialLog);
          if (fail) {
            delta = complement;
            n = Math.max(n - 1, 2);
            reduced = true;
            break;
          }
        }
      }

      if (!reduced) {
        if (n >= delta.size()) break; // 1-minimal — no chunk alone or complement helps
        n = Math.min(n * 2, delta.size());
      }
    }

    return delta;
  }

  /**
   * Applies one ddmin trial configuration (exactly {@code enabled} from {@code deltaAll} is
   * enabled, the rest disabled), reruns with real-time monitoring, and records the outcome.
   *
   * @return true if the target failure reproduced with this configuration
   */
  private static boolean testConfig(
      int trialNum,
      String kind,
      List<UUID> chunk,
      Set<UUID> enabled,
      List<UUID> deltaAll,
      Path runnerScript,
      Path working,
      Path shmDir,
      BlockIndex idx,
      Map<UUID, List<String>> originalLines,
      TestFailureLogParser.FailureMatch target,
      List<String> trialLog)
      throws IOException, InterruptedException {

    applyConfig(idx, originalLines, deltaAll, enabled);
    resetShmDir(shmDir);

    Path trialLogFile = working.resolve("daikonpp-test-filter-ddmin" + trialNum + ".log");
    System.out.println(
        "[DP-TEST-FILTER] Trial "
            + trialNum
            + " ("
            + kind
            + " "
            + chunk.size()
            + " of "
            + deltaAll.size()
            + "): executing, log -> "
            + trialLogFile);

    MonitorResult mr = runMonitored(runnerScript, working, trialLogFile, shmDir, target);

    String desc =
        "Trial "
            + trialNum
            + " ("
            + kind
            + " "
            + chunk.size()
            + " of "
            + deltaAll.size()
            + "): "
            + (mr.reproduced ? "FAIL (reproduced)" : "no reproduction")
            + " exit="
            + mr.exitCode;
    trialLog.add(desc);
    System.out.println("[DP-TEST-FILTER]   -> " + desc);

    return mr.reproduced;
  }

  /** Splits {@code list} into {@code n} contiguous, roughly-equal, non-empty chunks. */
  private static List<List<UUID>> splitInto(List<UUID> list, int n) {
    List<List<UUID>> chunks = new ArrayList<>();
    int size = list.size();
    int base = size / n;
    int rem = size % n;
    int idx = 0;
    for (int i = 0; i < n && idx < size; i++) {
      int chunkSize = base + (i < rem ? 1 : 0);
      if (chunkSize == 0) continue;
      chunks.add(new ArrayList<>(list.subList(idx, idx + chunkSize)));
      idx += chunkSize;
    }
    return chunks;
  }

  /**
   * Reads and stores the pristine (still-enabled) source lines of every block in {@code ids},
   * before any ddmin trial disables anything — so later trials can restore a block to its original
   * state instead of only ever being able to comment it out.
   */
  private static Map<UUID, List<String>> captureOriginalLines(BlockIndex idx, List<UUID> ids)
      throws IOException {
    Map<UUID, List<String>> out = new HashMap<>();
    Map<Path, List<UUID>> byFile = new HashMap<>();

    for (UUID id : ids) {
      Block b = idx.blocks.get(id);
      if (b == null) continue;
      byFile.computeIfAbsent(b.file, __ -> new ArrayList<>()).add(id);
    }

    for (Map.Entry<Path, List<UUID>> e : byFile.entrySet()) {
      List<String> lines = Files.readAllLines(e.getKey(), StandardCharsets.UTF_8);
      for (UUID id : e.getValue()) {
        Block b = idx.blocks.get(id);
        if (b == null) continue;
        List<String> block = new ArrayList<>();
        for (int i = b.beginLine; i <= b.endLine && i < lines.size(); i++) {
          block.add(lines.get(i));
        }
        out.put(id, block);
      }
    }

    return out;
  }

  /**
   * Sets the working copy's source so that exactly {@code enabled} (a subset of {@code deltaAll})
   * is active — every block is restored to its pristine text if enabled, or commented out if not.
   * Unlike {@link #disableIds}, this is idempotent and reversible: it can be called repeatedly with
   * different {@code enabled} sets across ddmin trials without needing a fresh project copy each
   * time.
   */
  private static void applyConfig(
      BlockIndex idx, Map<UUID, List<String>> originalLines, List<UUID> deltaAll, Set<UUID> enabled)
      throws IOException {

    Map<Path, List<UUID>> byFile = new HashMap<>();
    for (UUID id : deltaAll) {
      Block b = idx.blocks.get(id);
      if (b == null) continue;
      byFile.computeIfAbsent(b.file, __ -> new ArrayList<>()).add(id);
    }

    for (Map.Entry<Path, List<UUID>> e : byFile.entrySet()) {
      Path file = e.getKey();
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

      for (UUID id : e.getValue()) {
        Block b = idx.blocks.get(id);
        List<String> orig = originalLines.get(id);
        if (b == null || orig == null) continue;

        boolean shouldEnable = enabled.contains(id);
        for (int i = 0; i < orig.size(); i++) {
          int lineIdx = b.beginLine + i;
          if (lineIdx >= lines.size()) break;

          if (shouldEnable) {
            lines.set(lineIdx, orig.get(i));
          } else {
            lines.set(lineIdx, "// [DP] test-filter disabled :: " + orig.get(i));
          }
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
    }
  }

  // =====================================================================================
  // Real-time monitored execution (kill-on-detect)
  // =====================================================================================

  /** Outcome of a real-time monitored run: whether the target failure reproduced, and how. */
  private static final class MonitorResult {
    final boolean reproduced;
    final int exitCode;
    final Optional<TestFailureLogParser.FailureMatch> matched;

    MonitorResult(
        boolean reproduced, int exitCode, Optional<TestFailureLogParser.FailureMatch> matched) {
      this.reproduced = reproduced;
      this.exitCode = exitCode;
      this.matched = matched;
    }
  }

  /**
   * Runs the external test runner while checking each line of its output, as it streams in, against
   * {@code target}. The instant a line matches the <em>same</em> failure (see {@link
   * TestFailureLogParser#isSameFailure}), the process is killed immediately — no need to wait for
   * the rest of the suite, and no polling delay: detection latency is bounded only by how quickly
   * the OS delivers the child process's output to this reader.
   *
   * <p>Falls back to a full-log scan after the process exits on its own, in case a matching line
   * was written but not recognized in time (e.g. buffering) — so a genuine reproduction is never
   * missed, just possibly detected a little later than the real-time path would.
   *
   * @param script external test runner script
   * @param workDir working directory for execution
   * @param runLog output log file
   * @param shmDir shm directory for this run (already reset by the caller)
   * @param target the failure being chased; a run "reproduces" only if the same failure recurs
   * @return whether the target failure reproduced, and the run's exit code
   */
  private static MonitorResult runMonitored(
      Path script, Path workDir, Path runLog, Path shmDir, TestFailureLogParser.FailureMatch target)
      throws IOException, InterruptedException {

    if (!Files.isRegularFile(script)) {
      throw new IllegalArgumentException("[DP-TEST-FILTER] runner not found: " + script);
    }
    if (!Files.isExecutable(script)) {
      throw new IllegalArgumentException("[DP-TEST-FILTER] runner not executable: " + script);
    }

    Files.createDirectories(Optional.ofNullable(runLog.getParent()).orElse(Path.of(".")));

    Path invDir = workDir.resolve(".daikonpp-events");
    Files.createDirectories(invDir);

    ProcessBuilder pb = new ProcessBuilder(script.toAbsolutePath().toString());
    pb.directory(workDir.toFile());
    pb.redirectErrorStream(true);

    Map<String, String> env = pb.environment();
    env.put("DP_RUN_LOG", runLog.toAbsolutePath().toString());
    env.put("DP_INV_DIR", invDir.toAbsolutePath().toString());

    String shmPath = shmDir.toAbsolutePath().toString();
    env.put("DP_SHM_DIR", shmPath);

    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath() + " -DDP_SHM_DIR=" + shmPath;
    env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + jvmArgs).trim());
    env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + jvmArgs).trim());
    env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " " + jvmArgs).trim());

    Process p = pb.start();

    boolean reproduced = false;
    Optional<TestFailureLogParser.FailureMatch> matched = Optional.empty();

    try (BufferedReader r =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w =
            Files.newBufferedWriter(
                runLog,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

      String line;
      int lineNo = 0;
      while ((line = r.readLine()) != null) {
        lineNo++;
        w.write(line);
        w.newLine();
        w.flush();

        Optional<TestFailureLogParser.FailureMatch> m =
            TestFailureLogParser.matchLine(line, lineNo);
        if (m.isPresent() && TestFailureLogParser.isSameFailure(target, m.get())) {
          matched = m;
          reproduced = true;
          p.destroyForcibly();
          break;
        }
      }
    }

    int exit;
    if (reproduced) {
      p.waitFor(); // ensure the forcibly-killed process is fully reaped
      exit = -1;
    } else {
      exit = p.waitFor();
    }

    appendDpEvents(invDir, runLog);

    if (!reproduced && exit != 0) {
      // Safety net: exited non-zero without us catching a matching line in real time (e.g. the
      // child buffered its output). Do one full-log scan before concluding it didn't reproduce.
      String fullText = readIfExists(runLog);
      Optional<TestFailureLogParser.FailureMatch> fallback =
          TestFailureLogParser.firstFailure(fullText);
      if (fallback.isPresent() && TestFailureLogParser.isSameFailure(target, fallback.get())) {
        reproduced = true;
        matched = fallback;
      }
    }

    if (exit != 0) {
      Files.writeString(
          runLog,
          "\n[DP-TEST-FILTER] External runner exited with code " + exit + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }

    return new MonitorResult(reproduced, exit, matched);
  }

  // =====================================================================================
  // Shared helpers
  // =====================================================================================

  /**
   * Deletes and recreates {@code ex/}, {@code fail/}, and {@code current/} under {@code shmDir}.
   */
  private static void resetShmDir(Path shmDir) throws IOException {
    for (String sub : new String[] {"ex", "fail", "current"}) {
      Path dir = shmDir.resolve(sub);
      if (Files.isDirectory(dir)) {
        try (var s = Files.list(dir)) {
          for (Path p : (Iterable<Path>) s::iterator) {
            Files.deleteIfExists(p);
          }
        }
      }
      Files.createDirectories(dir);
    }
  }

  private static String readIfExists(Path file) throws IOException {
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  /**
   * Marker {@link edu.njit.jerse.daikonplusplus.JavaRunner} appends to a run log, once, only when
   * the external runner exits with a non-zero code (see {@code JavaRunner.runExternalScript}).
   */
  private static final String NON_ZERO_EXIT_MARKER = "[DP] External runner exited with code";

  private static final int TAIL_CHECK_BYTES = 8192;

  /**
   * Cheaply determines whether a run's process exited non-zero, without reading the whole log file
   * — only the last {@link #TAIL_CHECK_BYTES} bytes are inspected for {@link
   * #NON_ZERO_EXIT_MARKER}, which the runner always appends at the very end of the log when its
   * exit code is non-zero.
   *
   * @param runLog the run's log file
   * @return true if the marker was found in the file's tail (exit was non-zero)
   * @throws IOException if the file cannot be read
   */
  private static boolean exitedNonZero(Path runLog) throws IOException {
    if (!Files.isRegularFile(runLog)) return false;

    long size = Files.size(runLog);
    int tailSize = (int) Math.min(size, TAIL_CHECK_BYTES);
    if (tailSize == 0) return false;

    byte[] buf = new byte[tailSize];
    try (RandomAccessFile raf = new RandomAccessFile(runLog.toFile(), "r")) {
      raf.seek(size - tailSize);
      raf.readFully(buf);
    }

    return new String(buf, StandardCharsets.UTF_8).contains(NON_ZERO_EXIT_MARKER);
  }

  /**
   * Scans source files to locate invariant blocks and associate them with UUIDs.
   *
   * <p>Blocks are identified using begin/end markers and mapped to their source file locations.
   *
   * @param mainSrcRoot root of the source tree
   * @return index mapping invariant IDs to source blocks
   * @throws IOException if file traversal fails
   */
  private static BlockIndex scanInvariantBlocks(Path mainSrcRoot) throws IOException {
    BlockIndex index = new BlockIndex();

    try (var walk = Files.walk(mainSrcRoot)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        int i = 0;
        while (i < lines.size()) {
          String line = lines.get(i);

          boolean begin = line.contains(ONELINE_BEGIN) || line.contains(BLOCK_BEGIN);

          if (!begin) {
            i++;
            continue;
          }

          int beginLine = i;
          int endLine = -1;

          for (int j = i; j < lines.size(); j++) {
            String s = lines.get(j);
            if (s.contains(ONELINE_END) || s.contains(BLOCK_END)) {
              endLine = j;
              break;
            }
          }

          if (endLine < 0) {
            i++;
            continue;
          }

          StringBuilder blockText = new StringBuilder();
          for (int j = beginLine; j <= endLine; j++) {
            blockText.append(lines.get(j)).append('\n');
          }

          Matcher m = UUID_PATTERN.matcher(blockText.toString());
          while (m.find()) {
            UUID id = UUID.fromString(m.group());
            index.blocks.put(id, new Block(id, file, beginLine, endLine));
          }

          i = endLine + 1;
        }
      }
    }

    return index;
  }

  /**
   * Reads invariant-to-method mappings from the registry file.
   *
   * @param registryPath path to registry file
   * @return mapping from invariant UUID to method identifier
   * @throws IOException if reading fails
   */
  private static Map<UUID, String> readRegistryMethods(Path registryPath) throws IOException {
    Map<UUID, String> out = new HashMap<>();

    if (!Files.isRegularFile(registryPath)) return out;

    for (String line : Files.readAllLines(registryPath, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;

      Optional<String> idStr = extract(line, "\"id\":\"", "\"");
      Optional<String> element = extract(line, "\"element\":\"", "\"");

      if (idStr.isEmpty() || element.isEmpty()) continue;

      try {
        out.put(UUID.fromString(idStr.get()), unescapeJson(element.get()));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return out;
  }

  /**
   * Extracts a substring between two delimiters.
   *
   * @param s source string
   * @param start starting delimiter
   * @param end ending delimiter
   * @return extracted substring if present
   */
  private static Optional<String> extract(String s, String start, String end) {
    int i = s.indexOf(start);
    if (i < 0) return Optional.empty();

    int from = i + start.length();
    int j = s.indexOf(end, from);
    if (j < 0) return Optional.empty();

    return Optional.of(s.substring(from, j));
  }

  /**
   * Performs minimal unescaping of JSON string values.
   *
   * @param s escaped string
   * @return unescaped string
   */
  private static String unescapeJson(String s) {
    return s.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  /**
   * Executes an external test runner script and captures its output, waiting for it to run to
   * completion (no real-time detection) — used for the final confirming run once the culprit set is
   * already known.
   *
   * @param script executable test runner script
   * @param workDir working directory for execution
   * @param runLog output log file
   * @param shmDir shm directory for this run (already created/reset by the caller)
   * @return exit code of the test run
   * @throws IOException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  private static int runExternalTestRunner(Path script, Path workDir, Path runLog, Path shmDir)
      throws IOException, InterruptedException {

    if (!Files.isRegularFile(script)) {
      throw new IllegalArgumentException("[DP-TEST-FILTER] runner not found: " + script);
    }

    if (!Files.isExecutable(script)) {
      throw new IllegalArgumentException("[DP-TEST-FILTER] runner not executable: " + script);
    }

    Files.createDirectories(Optional.ofNullable(runLog.getParent()).orElse(Path.of(".")));

    Path invDir = workDir.resolve(".daikonpp-events");
    Files.createDirectories(invDir);

    ProcessBuilder pb = new ProcessBuilder(script.toAbsolutePath().toString());
    pb.directory(workDir.toFile());
    pb.redirectErrorStream(true);

    Map<String, String> env = pb.environment();
    env.put("DP_RUN_LOG", runLog.toAbsolutePath().toString());
    env.put("DP_INV_DIR", invDir.toAbsolutePath().toString());

    String shmPath = shmDir.toAbsolutePath().toString();
    env.put("DP_SHM_DIR", shmPath);

    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath() + " -DDP_SHM_DIR=" + shmPath;

    env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + jvmArgs).trim());
    env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + jvmArgs).trim());
    env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " " + jvmArgs).trim());

    Process p = pb.start();

    try (BufferedReader r =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w =
            Files.newBufferedWriter(
                runLog,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

      String line;
      while ((line = r.readLine()) != null) {
        w.write(line);
        w.newLine();
        w.flush();
      }
    }

    int exit = p.waitFor();

    appendDpEvents(invDir, runLog);

    if (exit != 0) {
      Files.writeString(
          runLog,
          "\n[DP-TEST-FILTER] External runner exited with code " + exit + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }

    return exit;
  }

  /**
   * Appends recorded invariant event files to the main run log.
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
   * Creates a new working copy from an existing snapshot.
   *
   * @param snapshot source snapshot
   * @param prefix prefix for naming the new copy
   * @return path to new copy
   * @throws IOException if copying fails
   */
  private static Path freshCopy(Path snapshot, String prefix) throws IOException {
    Path parent = snapshot.getParent();
    if (parent == null) {
      parent = Path.of(System.getProperty("java.io.tmpdir"));
    }

    Path dst = parent.resolve(prefix + "-" + System.nanoTime());
    copyTree(snapshot, dst);
    return dst;
  }

  /**
   * Recursively copies a directory tree.
   *
   * <p>Prevents copying into overlapping directories.
   *
   * @param from source directory
   * @param to destination directory
   * @throws IOException if copying fails
   */
  private static void copyTree(Path from, Path to) throws IOException {
    Path src = from.toAbsolutePath().normalize();
    Path dst = to.toAbsolutePath().normalize();

    if (dst.startsWith(src) || src.startsWith(dst)) {
      throw new IllegalStateException("Refusing overlapping copy: " + src + " -> " + dst);
    }

    Files.walkFileTree(
        src,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dst.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path target = dst.resolve(src.relativize(file));
            Files.copy(
                file,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Index of invariant blocks keyed by their UUID. */
  private static final class BlockIndex {
    final Map<UUID, Block> blocks = new HashMap<>();
  }

  /**
   * Represents a contiguous invariant block in a source file.
   *
   * <p>Includes file location and line range.
   */
  private static final class Block {
    final UUID id;
    final Path file;
    final int beginLine;
    final int endLine;

    Block(UUID id, Path file, int beginLine, int endLine) {
      this.id = id;
      this.file = file;
      this.beginLine = beginLine;
      this.endLine = endLine;
    }
  }

  /**
   * Result of test-based invariant filtering.
   *
   * <p>Contains the final project state, removed invariants, and execution outcome.
   */
  public static final class Result {
    public final Path snapshotProjectRoot;
    public final Path finalProjectRoot;
    public final Path finalMainSrcRoot;
    public final Path finalRunLog;
    public final Set<UUID> removedIds;
    public final List<String> removedMethodBatches;
    public final int finalExitCode;

    /**
     * @param snapshotProjectRoot initial snapshot of the injected project
     * @param finalProjectRoot project after filtering
     * @param finalMainSrcRoot final main source directory
     * @param finalRunLog log from final test execution
     * @param removedIds set of invariant IDs that were disabled
     * @param removedMethodBatches human-readable descriptions of each disabled invariant and why
     * @param finalExitCode exit code of final test run
     */
    Result(
        Path snapshotProjectRoot,
        Path finalProjectRoot,
        Path finalMainSrcRoot,
        Path finalRunLog,
        Set<UUID> removedIds,
        List<String> removedMethodBatches,
        int finalExitCode) {

      this.snapshotProjectRoot = snapshotProjectRoot;
      this.finalProjectRoot = finalProjectRoot;
      this.finalMainSrcRoot = finalMainSrcRoot;
      this.finalRunLog = finalRunLog;
      this.removedIds = Set.copyOf(removedIds);
      this.removedMethodBatches = List.copyOf(removedMethodBatches);
      this.finalExitCode = finalExitCode;
    }
  }
}
