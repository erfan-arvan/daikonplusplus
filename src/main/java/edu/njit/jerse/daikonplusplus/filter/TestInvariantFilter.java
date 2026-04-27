package edu.njit.jerse.daikonplusplus.filter;

import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

public final class TestInvariantFilter {

  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private TestInvariantFilter() {}

  public static Result run(
      Path injectedProjectRoot,
      Path mainSrcRoot,
      Path registryPath,
      Path initialRunLog,
      Path runnerScript,
      int methodBatchSize)
      throws Exception {

    System.out.println("\n[DP-TEST-FILTER] ===== START TEST-BASED FILTERING =====");

    Path snapshot = makeSnapshot(injectedProjectRoot);

    Path snapshotMainSrc =
        snapshot.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();

    Map<UUID, String> idToMethod = readRegistryMethods(registryPath);
    Set<UUID> executed = LogParser.readExecutedIds(initialRunLog);

    BlockIndex index = scanInvariantBlocks(snapshotMainSrc);

    Map<String, List<UUID>> methodGroups = new TreeMap<>();

    for (UUID id : executed) {
      String method = idToMethod.get(id);
      if (method == null) continue;
      if (!index.blocks.containsKey(id)) continue;

      methodGroups.computeIfAbsent(method, __ -> new ArrayList<>()).add(id);
    }

    List<List<UUID>> batches = makeMethodBatches(methodGroups, methodBatchSize);

    System.out.println("[DP-TEST-FILTER] snapshot=" + snapshot);
    System.out.println("[DP-TEST-FILTER] executed ids=" + executed.size());
    System.out.println("[DP-TEST-FILTER] ids with source blocks=" + index.blocks.size());
    System.out.println("[DP-TEST-FILTER] method groups=" + methodGroups.size());
    System.out.println("[DP-TEST-FILTER] batches=" + batches.size());

    Set<UUID> removed = new LinkedHashSet<>();
    List<String> removedMethods = new ArrayList<>();

    int pass = 1;

    for (List<UUID> batch : batches) {
      Path attempt = freshCopy(snapshot, "test-filter-pass-" + pass);
      Path attemptMainSrc =
          attempt.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();

      BlockIndex attemptIndex = scanInvariantBlocks(attemptMainSrc);

      disableIds(attemptIndex, batch);

      Path attemptLog = attempt.resolve("daikonpp-test-filter-pass-" + pass + ".log");

      int exit = runExternalTestRunner(runnerScript, attempt, attemptLog);

      String batchLabel = describeBatch(batch, idToMethod);

      if (exit == 0) {
        removed.addAll(batch);
        removedMethods.add(batchLabel);
        System.out.println(
            "[DP-TEST-FILTER] PASS " + pass + " succeeded after disabling: " + batchLabel);
      } else {
        System.out.println(
            "[DP-TEST-FILTER] PASS " + pass + " still failed after disabling: " + batchLabel);
      }

      pass++;
    }

    Path finalProject = freshCopy(snapshot, "test-filter-final");
    Path finalMainSrc =
        finalProject.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();

    BlockIndex finalIndex = scanInvariantBlocks(finalMainSrc);
    disableIds(finalIndex, removed);

    Path finalLog = finalProject.resolve("daikonpp-test-filter-final.log");
    int finalExit = runExternalTestRunner(runnerScript, finalProject, finalLog);

    System.out.println("[DP-TEST-FILTER] removed ids=" + removed.size());
    System.out.println("[DP-TEST-FILTER] final test exit=" + finalExit);
    System.out.println("[DP-TEST-FILTER] final project=" + finalProject);
    System.out.println("[DP-TEST-FILTER] final log=" + finalLog);
    System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

    return new Result(
        snapshot, finalProject, finalMainSrc, finalLog, removed, removedMethods, finalExit);
  }

  private static List<List<UUID>> makeMethodBatches(
      Map<String, List<UUID>> methodGroups, int methodBatchSize) {

    int size = Math.max(1, methodBatchSize);

    List<Map.Entry<String, List<UUID>>> methods = new ArrayList<>(methodGroups.entrySet());

    methods.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

    List<List<UUID>> batches = new ArrayList<>();

    for (int i = 0; i < methods.size(); i += size) {
      List<UUID> batch = new ArrayList<>();

      for (int j = i; j < Math.min(i + size, methods.size()); j++) {
        batch.addAll(methods.get(j).getValue());
      }

      batches.add(batch);
    }

    return batches;
  }

  private static String describeBatch(List<UUID> ids, Map<UUID, String> idToMethod) {
    Set<String> methods = new TreeSet<>();
    for (UUID id : ids) {
      String m = idToMethod.get(id);
      if (m != null) methods.add(m);
    }
    return methods.toString();
  }

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

  private static Optional<String> extract(String s, String start, String end) {
    int i = s.indexOf(start);
    if (i < 0) return Optional.empty();

    int from = i + start.length();
    int j = s.indexOf(end, from);
    if (j < 0) return Optional.empty();

    return Optional.of(s.substring(from, j));
  }

  private static String unescapeJson(String s) {
    return s.replace("\\\"", "\"").replace("\\\\", "\\");
  }

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

  private static Path freshCopy(Path snapshot, String prefix) throws IOException {
    Path parent = snapshot.getParent();
    if (parent == null) {
      parent = Path.of(System.getProperty("java.io.tmpdir"));
    }

    Path dst = parent.resolve(prefix + "-" + System.nanoTime());
    copyTree(snapshot, dst);
    return dst;
  }

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

  private static final class BlockIndex {
    final Map<UUID, Block> blocks = new HashMap<>();
  }

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

  public static final class Result {
    public final Path snapshotProjectRoot;
    public final Path finalProjectRoot;
    public final Path finalMainSrcRoot;
    public final Path finalRunLog;
    public final Set<UUID> removedIds;
    public final List<String> removedMethodBatches;
    public final int finalExitCode;

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
