package edu.njit.jerse.daikonplusplus.filter;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.*;

/**
 * Test-based invariant filtering that removes invariants causing test failures.
 *
 * <p>Strategy: run the external test suite normally first (the {@code initialRunLog} passed in has
 * already been produced by the caller). If that log contains no recognized test-failure signature
 * ({@link TestFailureLogParser}), there is nothing to filter and the project is left untouched.
 *
 * <p>If a test failure is found, the suite is rerun on a working copy with shm-based invariant
 * tracking enabled so each run's execution order can be recovered. Each time a rerun's log still
 * shows a test-failure signature, the invariant that was executed <em>last</em> in that run (the
 * one most likely responsible, mirroring how the stale/timeout detector blames the invariant that
 * was mid-evaluation when a hang is killed) is disabled by commenting out its source block, and the
 * suite is rerun again. This repeats until either a run passes cleanly, or the search is exhausted
 * (no further invariant can be identified/disabled — e.g. every executed invariant has already been
 * tried, or a run fails without a recognized failure signature at all, in which case the failure
 * can't be safely attributed to an invariant).
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
   * <p>The algorithm: - Checks the initial run's log for a recognized test-failure signature; if
   * none is found, returns immediately with nothing removed - Otherwise, reruns the suite on a
   * working copy with shm tracking enabled - On each rerun that still shows a failure signature,
   * disables the last-executed invariant and reruns - Stops on a clean pass, or when the search is
   * exhausted
   *
   * @param injectedProjectRoot root of the project with injected invariants
   * @param mainSrcRoot source root containing instrumented Java files
   * @param registryPath registry mapping invariant IDs to program elements
   * @param initialRunLog log from the initial execution, already produced by the caller
   * @param runnerScript external test runner script
   * @param methodBatchSize unused by the current (last-executed) strategy; kept for source
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
    System.out.println("[DP-TEST-FILTER] strategy = disable-last-executed-on-test-failure");

    Map<UUID, String> idToMethod = readRegistryMethods(registryPath);

    System.out.println("[DP-TEST-FILTER] Reading initial run log: " + initialRunLog);
    String initialLogText = readIfExists(initialRunLog);
    Optional<TestFailureLogParser.FailureMatch> initialFailure =
        TestFailureLogParser.firstFailure(initialLogText);

    if (initialFailure.isEmpty()) {
      System.out.println(
          "[DP-TEST-FILTER] No recognized test-failure signature in initial run log — nothing to "
              + "filter (skipping project copy)");
      System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

      return new Result(
          injectedProjectRoot,
          injectedProjectRoot,
          mainSrcRoot,
          initialRunLog,
          Set.of(),
          List.of(),
          0);
    }

    System.out.println(
        "[DP-TEST-FILTER] Initial run failed ["
            + initialFailure.get().format()
            + "] :: "
            + initialFailure.get().line());

    System.out.println(
        "[DP-TEST-FILTER] Creating working copy from " + injectedProjectRoot + " ...");
    long copyStart = System.nanoTime();
    Path snapshot = injectedProjectRoot;
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

    Set<UUID> removed = new LinkedHashSet<>();
    List<String> removalLog = new ArrayList<>();

    Path finalLog = initialRunLog;
    int finalExit = -1;
    int iteration = 0;

    while (true) {
      iteration++;
      resetShmDir(shmDir);

      Path iterLog = working.resolve("daikonpp-test-filter-iter" + iteration + ".log");
      System.out.println(
          "[DP-TEST-FILTER] Run " + iteration + ": executing test runner, log -> " + iterLog);
      int exit = runExternalTestRunner(runnerScript, working, iterLog, shmDir);
      String iterLogText = readIfExists(iterLog);

      finalLog = iterLog;
      finalExit = exit;

      Optional<TestFailureLogParser.FailureMatch> failure =
          TestFailureLogParser.firstFailure(iterLogText);

      if (failure.isEmpty()) {
        if (exit == 0) {
          System.out.println("[DP-TEST-FILTER] Run " + iteration + ": PASSED — filtering complete");
        } else {
          System.out.println(
              "[DP-TEST-FILTER] Run "
                  + iteration
                  + ": exit="
                  + exit
                  + " but no recognized test-failure signature — cannot safely attribute to an "
                  + "invariant, stopping");
        }
        break;
      }

      System.out.println(
          "[DP-TEST-FILTER] Run "
              + iteration
              + ": test failure detected ["
              + failure.get().format()
              + "] :: "
              + failure.get().line());

      Optional<UUID> lastExecuted = findLastExecuted(shmDir, iterLog, removed);

      if (lastExecuted.isEmpty()) {
        System.out.println(
            "[DP-TEST-FILTER] Run "
                + iteration
                + ": no not-yet-disabled executed invariant could be identified — exhausted, "
                + "stopping");
        break;
      }

      UUID uuid = lastExecuted.get();
      removed.add(uuid);

      BlockIndex idx = scanInvariantBlocks(workingMainSrc);
      disableIds(idx, List.of(uuid));

      String method = idToMethod.getOrDefault(uuid, "(unknown method)");
      String entry =
          "Run "
              + iteration
              + ": disabled "
              + uuid
              + " ["
              + method
              + "] after test failure ("
              + failure.get().format()
              + "): "
              + failure.get().line();
      removalLog.add(entry);
      System.out.println("[DP-TEST-FILTER]   -> " + entry);
    }

    System.out.println("[DP-TEST-FILTER] removed ids=" + removed.size());
    System.out.println("[DP-TEST-FILTER] final exit=" + finalExit);
    System.out.println("[DP-TEST-FILTER] final project=" + working);
    System.out.println("[DP-TEST-FILTER] final log=" + finalLog);
    System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

    return new Result(snapshot, working, workingMainSrc, finalLog, removed, removalLog, finalExit);
  }

  /**
   * Identifies the invariant executed last (most recently) in the most recent run, excluding any
   * UUID already in {@code alreadyRemoved}.
   *
   * <p>Primary source: {@code shmDir/ex/} filenames, ranked by file-modification time — each
   * invariant check writes its marker to this directory the moment it starts executing (see {@code
   * daikonpp.DpRuntime.recordExecuted}), so the most recently written file identifies the invariant
   * that ran last before the test failure surfaced.
   *
   * <p>Fallback: if the shm directory is empty (e.g. shm tracking unavailable), falls back to the
   * last {@code INV_EXD:<uuid>} entry in the run log.
   *
   * @param shmDir shm directory used for this run (already reset before the run started)
   * @param runLog this run's log file
   * @param alreadyRemoved invariants already disabled in earlier iterations
   * @return the last-executed, not-yet-disabled invariant, if any
   */
  private static Optional<UUID> findLastExecuted(Path shmDir, Path runLog, Set<UUID> alreadyRemoved)
      throws IOException {
    Path exDir = shmDir.resolve("ex");

    UUID best = null;
    FileTime bestTime = null;

    if (Files.isDirectory(exDir)) {
      try (var s = Files.list(exDir)) {
        for (Path p : (Iterable<Path>) s::iterator) {
          Path fn = p.getFileName();
          if (fn == null) continue;

          UUID id;
          try {
            id = UUID.fromString(fn.toString());
          } catch (IllegalArgumentException ignore) {
            continue;
          }

          if (alreadyRemoved.contains(id)) continue;

          FileTime t = Files.getLastModifiedTime(p);
          if (bestTime == null || t.compareTo(bestTime) > 0) {
            bestTime = t;
            best = id;
          }
        }
      }
    }

    if (best != null) return Optional.of(best);

    // Fallback: last INV_EXD entry in the log, skipping already-removed UUIDs.
    Optional<UUID> logBased = LogParser.readLastExecutedId(runLog);
    if (logBased.isPresent() && !alreadyRemoved.contains(logBased.get())) {
      return logBased;
    }

    return Optional.empty();
  }

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
   * Disables invariant blocks corresponding to the given IDs by commenting them out.
   *
   * <p>Blocks are grouped per file and processed in reverse order to preserve line offsets during
   * modification.
   *
   * @param index index of invariant blocks
   * @param ids invariant IDs to disable
   * @throws IOException if file modification fails
   */
  private static void disableIds(BlockIndex index, Collection<UUID> ids) throws IOException {
    Map<Path, List<Block>> byFile = new HashMap<>();

    for (UUID id : ids) {
      Block b = index.blocks.get(id);
      if (b == null) continue;
      byFile.computeIfAbsent(b.file, __ -> new ArrayList<>()).add(b);
    }

    for (Map.Entry<Path, List<Block>> e : byFile.entrySet()) {
      Path file = e.getKey();
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

      List<Block> blocks = e.getValue();
      blocks.sort((a, b) -> Integer.compare(b.beginLine, a.beginLine));

      for (Block b : blocks) {
        for (int i = b.beginLine; i <= b.endLine && i < lines.size(); i++) {
          String line = lines.get(i);
          if (!line.trim().startsWith("// [DP] test-filter disabled")) {
            lines.set(i, "// [DP] test-filter disabled :: " + line);
          }
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
    }
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
   * Executes an external test runner script and captures its output.
   *
   * <p>The method sets required environment variables — including {@code DP_SHM_DIR}, so each
   * invariant's execution marker is timestamped and the last-executed one can be recovered after
   * the run — and appends invariant execution events to the log.
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
