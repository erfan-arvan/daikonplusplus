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
 * <p>This class isolates the culprit invariants via <a
 * href="https://www.debuggingbook.org/html/DeltaDebugger.html">delta debugging</a> ({@code ddmin}):
 * starting from every invariant that executed in the initial run, it repeatedly tests shrinking
 * subsets (and their complements) against fresh copies of the project, keeping only the failure
 * signature that matches the original ({@link TestFailureLogParser#isSameFailure}) as evidence of
 * reproduction, until a 1-minimal culprit set is reached — i.e. one where no single invariant can
 * be dropped without the failure stopping. Before searching, a sanity trial confirms the failure is
 * actually attributable to some invariant (disabling every candidate must make it stop
 * reproducing); otherwise the failure is left alone.
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
   * <p>The algorithm: - Takes a snapshot of the injected project - Identifies executed invariants
   * from the initial run log and the failure signature they reproduced - Sanity-checks that the
   * failure is attributable to some invariant - Runs {@code ddmin} delta debugging to isolate a
   * 1-minimal culprit set - Disables exactly that set in the final result
   *
   * @param injectedProjectRoot root of the project with injected invariants
   * @param mainSrcRoot source root containing instrumented Java files
   * @param registryPath registry mapping invariant IDs to program elements
   * @param initialRunLog log from the initial execution containing executed invariant IDs and, if
   *     the run failed, the failure to isolate
   * @param runnerScript external test runner script
   * @param ddminInitialChunks number of chunks {@code ddmin} splits the candidate pool into at the
   *     start of its search (clamped to at least 2); classic {@code ddmin} starts at {@code n = 2}
   * @return result object describing the final filtered project and removed invariants
   * @throws Exception if execution fails
   */
  public static Result run(
      Path injectedProjectRoot,
      Path mainSrcRoot,
      Path registryPath,
      Path initialRunLog,
      Path runnerScript,
      int ddminInitialChunks)
      throws Exception {

    System.out.println(
        "\n[DP-TEST-FILTER] ===== START TEST-BASED FILTERING (delta debugging / ddmin) =====");

    Path snapshot = makeSnapshot(injectedProjectRoot);

    Path relMainSrc = injectedProjectRoot.relativize(mainSrcRoot);
    Path snapshotMainSrc = snapshot.resolve(relMainSrc).normalize();

    Map<UUID, String> idToMethod = readRegistryMethods(registryPath);
    Set<UUID> executed = LogParser.readExecutedIds(initialRunLog);

    BlockIndex index = scanInvariantBlocks(snapshotMainSrc);

    List<UUID> candidates = new ArrayList<>();
    for (UUID id : executed) {
      if (index.blocks.containsKey(id)) {
        candidates.add(id);
      }
    }
    candidates.sort(Comparator.comparing(UUID::toString));

    String initialLogText =
        Files.isRegularFile(initialRunLog)
            ? Files.readString(initialRunLog, StandardCharsets.UTF_8)
            : "";
    Optional<TestFailureLogParser.FailureMatch> target =
        TestFailureLogParser.firstFailure(initialLogText);

    System.out.println("[DP-TEST-FILTER] snapshot=" + snapshot);
    System.out.println("[DP-TEST-FILTER] executed ids=" + executed.size());
    System.out.println(
        "[DP-TEST-FILTER] candidate ids (executed with source blocks)=" + candidates.size());
    System.out.println(
        "[DP-TEST-FILTER] target failure="
            + target.map(f -> f.format() + " :: " + f.line()).orElse("(none recognized)"));

    Set<UUID> removed = new LinkedHashSet<>();
    List<String> removedMethodBatches = new ArrayList<>();
    int[] trialCounter = {0};

    if (target.isEmpty()) {
      System.out.println(
          "[DP-TEST-FILTER] No recognized failure in the initial run — nothing to isolate");
    } else if (candidates.isEmpty()) {
      System.out.println("[DP-TEST-FILTER] No candidate invariants — cannot attribute the failure");
    } else {
      boolean stillFailsWithAllDisabled =
          testConfig(
              ++trialCounter[0],
              "sanity-all-disabled",
              Set.of(),
              candidates,
              runnerScript,
              snapshot,
              relMainSrc,
              target.get());

      if (stillFailsWithAllDisabled) {
        System.out.println(
            "[DP-TEST-FILTER] Failure still reproduces with every candidate invariant disabled — "
                + "not caused by any injected invariant — not attributable");
      } else {
        List<UUID> culprits =
            ddmin(
                runnerScript,
                snapshot,
                relMainSrc,
                candidates,
                target.get(),
                Math.max(2, ddminInitialChunks),
                trialCounter);

        removed.addAll(culprits);
        removedMethodBatches.add(describeBatch(culprits, idToMethod));

        System.out.println(
            "[DP-TEST-FILTER] delta debugging isolated "
                + culprits.size()
                + " culprit invariant(s) after "
                + trialCounter[0]
                + " trial(s): "
                + describeBatch(culprits, idToMethod));
      }
    }

    Path finalProject = freshCopy(snapshot, "test-filter-final");
    Path finalMainSrc = finalProject.resolve(relMainSrc).normalize();

    BlockIndex finalIndex = scanInvariantBlocks(finalMainSrc);
    disableIds(finalIndex, removed);

    Path finalLog = finalProject.resolve("daikonpp-test-filter-final.log");
    int finalExit = runExternalTestRunner(runnerScript, finalProject, finalLog);

    System.out.println("[DP-TEST-FILTER] removed ids=" + removed.size());
    System.out.println("[DP-TEST-FILTER] trials used=" + trialCounter[0]);
    System.out.println("[DP-TEST-FILTER] final test exit=" + finalExit);
    System.out.println("[DP-TEST-FILTER] final project=" + finalProject);
    System.out.println("[DP-TEST-FILTER] final log=" + finalLog);
    System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

    return new Result(
        snapshot, finalProject, finalMainSrc, finalLog, removed, removedMethodBatches, finalExit);
  }

  /**
   * Classic {@code ddmin}: isolates a 1-minimal subset of {@code deltaInit} whose presence
   * (enabled, with every other candidate disabled) reproduces {@code target}. {@code deltaInit} is
   * assumed to reproduce the failure when fully enabled — that's how it was built (from a run that
   * just reproduced it, confirmed attributable by the caller's sanity trial).
   *
   * <p>Split {@code delta} into {@code n} chunks; try each chunk alone; if none reproduces, try
   * each chunk's complement (i.e. everything except that chunk); if that doesn't narrow things
   * either, increase granularity (double {@code n}) and retry, until {@code n} reaches {@code
   * delta.size()} with no further progress — at which point {@code delta} is 1-minimal.
   *
   * @return the isolated minimal culprit set
   */
  private static List<UUID> ddmin(
      Path runnerScript,
      Path snapshot,
      Path relMainSrc,
      List<UUID> deltaInit,
      TestFailureLogParser.FailureMatch target,
      int initialN,
      int[] trialCounter)
      throws IOException, InterruptedException {

    List<UUID> delta = new ArrayList<>(deltaInit);
    int n = Math.min(initialN, delta.size());

    while (delta.size() > 1) {
      n = Math.min(n, delta.size());
      List<List<UUID>> chunks = splitInto(delta, n);

      boolean reduced = false;

      for (List<UUID> chunk : chunks) {
        if (chunk.isEmpty()) continue;

        boolean fail =
            testConfig(
                ++trialCounter[0],
                "alone (" + chunk.size() + "/" + delta.size() + ")",
                new LinkedHashSet<>(chunk),
                deltaInit,
                runnerScript,
                snapshot,
                relMainSrc,
                target);

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

          List<UUID> complement = new ArrayList<>(delta);
          complement.removeAll(chunk);

          boolean fail =
              testConfig(
                  ++trialCounter[0],
                  "without (" + chunk.size() + "/" + delta.size() + ")",
                  new LinkedHashSet<>(complement),
                  deltaInit,
                  runnerScript,
                  snapshot,
                  relMainSrc,
                  target);

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
   * Runs one trial: exactly {@code enabled} (a subset of {@code allCandidates}) stays active in a
   * fresh copy of {@code snapshot}, everything else in {@code allCandidates} is disabled by
   * commenting its source block out, and the external test runner is executed once against it.
   *
   * <p>A trial counts as reproducing {@code target} only if the run fails <em>and</em> the first
   * recognized failure in its log is the same failure ({@link TestFailureLogParser#isSameFailure})
   * — a different, unrelated failure (e.g. a flaky, unrelated test) must not be mistaken for
   * evidence about {@code target}.
   *
   * @return true if {@code target} reproduced with this configuration
   */
  private static boolean testConfig(
      int trialNum,
      String kind,
      Set<UUID> enabled,
      List<UUID> allCandidates,
      Path runnerScript,
      Path snapshot,
      Path relMainSrc,
      TestFailureLogParser.FailureMatch target)
      throws IOException, InterruptedException {

    List<UUID> toDisable = new ArrayList<>();
    for (UUID id : allCandidates) {
      if (!enabled.contains(id)) {
        toDisable.add(id);
      }
    }

    Path attempt = freshCopy(snapshot, "test-filter-trial" + trialNum);
    try {
      Path attemptMainSrc = attempt.resolve(relMainSrc).normalize();
      BlockIndex attemptIndex = scanInvariantBlocks(attemptMainSrc);
      disableIds(attemptIndex, toDisable);

      Path attemptLog = attempt.resolve("daikonpp-test-filter-trial" + trialNum + ".log");
      int exit = runExternalTestRunner(runnerScript, attempt, attemptLog);

      boolean reproduced = false;
      if (exit != 0) {
        String logText = Files.readString(attemptLog, StandardCharsets.UTF_8);
        Optional<TestFailureLogParser.FailureMatch> found =
            TestFailureLogParser.firstFailure(logText);
        reproduced = found.isPresent() && TestFailureLogParser.isSameFailure(target, found.get());
      }

      System.out.println(
          "[DP-TEST-FILTER]   Trial "
              + trialNum
              + " ("
              + kind
              + "): "
              + (reproduced ? "FAIL (reproduced)" : "no reproduction")
              + " exit="
              + exit);

      return reproduced;
    } finally {
      deleteTreeQuietly(attempt);
    }
  }

  /**
   * Splits {@code list} into {@code n} contiguous, roughly-equal, non-empty chunks (fewer than
   * {@code n} if {@code list} is too small to split that finely).
   */
  private static List<List<UUID>> splitInto(List<UUID> list, int n) {
    List<List<UUID>> chunks = new ArrayList<>();
    int size = list.size();
    int chunkCount = Math.max(1, Math.min(n, size));

    int base = size / chunkCount;
    int remainder = size % chunkCount;

    int idx = 0;
    for (int i = 0; i < chunkCount; i++) {
      int len = base + (i < remainder ? 1 : 0);
      chunks.add(new ArrayList<>(list.subList(idx, idx + len)));
      idx += len;
    }

    return chunks;
  }

  /**
   * Produces a human-readable description of a set of invariants.
   *
   * @param ids invariant IDs
   * @param idToMethod mapping from invariant IDs to method identifiers
   * @return string representation of affected methods
   */
  private static String describeBatch(Collection<UUID> ids, Map<UUID, String> idToMethod) {
    Set<String> methods = new TreeSet<>();
    for (UUID id : ids) {
      String m = idToMethod.get(id);
      if (m != null) methods.add(m);
    }
    return methods.toString();
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
   * <p>The method sets required environment variables and appends invariant execution events to the
   * log.
   *
   * @param script executable test runner script
   * @param workDir working directory for execution
   * @param runLog output log file
   * @return exit code of the test run
   * @throws IOException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  private static int runExternalTestRunner(Path script, Path workDir, Path runLog)
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

    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath();

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
   * Creates a full copy of the project for isolated modification.
   *
   * @param projectRoot original project root
   * @return path to snapshot copy
   * @throws IOException if copying fails
   */
  private static Path makeSnapshot(Path projectRoot) throws IOException {
    Path parent = projectRoot.getParent();
    if (parent == null) {
      parent = Path.of(System.getProperty("java.io.tmpdir"));
    }

    Path snapshot =
        parent.resolve(projectRoot.getFileName() + "-injected-snapshot-" + System.nanoTime());
    copyTree(projectRoot, snapshot);
    return snapshot;
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

  /**
   * Recursively deletes a directory tree, swallowing any failure — used to clean up per-trial
   * working copies, where a leftover directory is a nuisance, not a correctness problem.
   *
   * @param root directory to delete
   */
  private static void deleteTreeQuietly(Path root) {
    try {
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
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
    }
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
     * @param removedMethodBatches descriptions of removed method groups
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
