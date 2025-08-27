package edu.njit.jerse.daikonplusplus;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import edu.njit.jerse.daikonplusplus.config.*;
import edu.njit.jerse.daikonplusplus.inject.FileWriteCoordinator;
import edu.njit.jerse.daikonplusplus.inject.JavaParserInjector;
import edu.njit.jerse.daikonplusplus.llm.OpenAIInvariantGeneratorClient;
import edu.njit.jerse.daikonplusplus.model.*;
import edu.njit.jerse.daikonplusplus.parse.JavaProjectScanner;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
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
import java.util.stream.Collectors;

/**
 * CLI entrypoint for Daikon++ with compile-and-run.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * java -jar daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxInvariantsPerPoint] [-- program args...]
 * }</pre>
 *
 * <ul>
 *   <li><b>srcRoot</b>: path to Java sources
 *   <li><b>classpath</b>: paths needed to compile/run (use {@code :} on Unix/macOS, {@code ;} on
 *       Windows). You do NOT need to include this tool's own JAR; it is auto-appended.
 *   <li><b>mainClass</b>: e.g., {@code com.example.Main}
 *   <li><b>maxInvariantsPerPoint</b>: optional (default 5)
 *   <li>Anything after {@code --} is passed to your program as args.
 * </ul>
 *
 * <h2>Pipeline</h2>
 *
 * scan → LLM invariants → inject → <b>javac</b> → <b>java</b> → parse log → print held ENTRY
 * invariants per method.
 */
public final class App {

  private static final DpConfig CFG = DpConfig.fromEnv();

  private static final boolean DEBUG = CFG.debug();

  // at top-level in App
  private static final java.util.Set<String> RUN_DEDUP =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static String keyFor(ProgramPoint pt, String expr) {
    String norm = expr.trim().replaceAll("\\s+", " "); // normalize whitespace
    return pt.kind().name() + "|" + pt.elementId().toString() + "|" + norm;
  }

  private static void dbg(String msg) {
    if (DEBUG) System.out.println("[DP] " + msg);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println(
          "Usage: java -jar daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]");
      System.exit(2);
    }

    int i = 0;
    final Path userSrcRoot = Path.of(args[i++]).toAbsolutePath().normalize();
    if (!Files.isDirectory(userSrcRoot)) {
      System.err.println("Not a directory: " + userSrcRoot);
      System.exit(2);
    }

    // make a clean working copy and use that for the rest of the pipeline
    final Path srcRoot = prepareWorkingCopy(userSrcRoot);

    final String userClasspath = args[i++]; // colon/semicolon separated
    final String mainClass = args[i++];
    final int maxK =
        (i < args.length && !args[i].equals("--")) ? Math.max(1, Integer.parseInt(args[i++])) : 5;

    final List<String> programArgs = new ArrayList<>();
    if (i < args.length && args[i].equals("--")) {
      for (i = i + 1; i < args.length; i++) programArgs.add(args[i]);
    }

    if (!Files.isDirectory(srcRoot)) {
      System.err.println("Not a directory: " + srcRoot);
      System.exit(2);
    }

    final DpConfig cfg = DpConfig.fromEnv();
    if (CFG.registryReset()) {
      try {
        java.nio.file.Files.deleteIfExists(cfg.registryPath());
        System.out.println(">>> Registry reset: " + cfg.registryPath().toAbsolutePath());
      } catch (java.io.IOException ioe) {
        System.err.println("Warning: couldn't delete registry: " + ioe.getMessage());
      }
    }

    final JavaProjectScanner scanner = new JavaProjectScanner();
    final OpenAIInvariantGeneratorClient llm = new OpenAIInvariantGeneratorClient(maxK);
    final InvariantRegistry registry = new InvariantRegistry(cfg.registryPath());
    final JavaParserInjector injector = new JavaParserInjector(new FileWriteCoordinator());

    System.out.println(">>> Scanning sources under (WORKING COPY): " + srcRoot);
    final List<ProgramPoint> points = scanner.scanMethodEntryExit(srcRoot);

    long nEntry = points.stream().filter(p -> p.kind() == ProgramPointKind.METHOD_ENTRY).count();
    long nExit = points.stream().filter(p -> p.kind() == ProgramPointKind.METHOD_EXIT).count();

    System.out.println(
        ">>> Points — ENTRY: " + nEntry + "  EXIT: " + nExit + "  TOTAL: " + points.size());

    // --- Phase 1: parallel LLM proposals (timeout + progress + cancellation)
    final ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
    final CompletionService<List<InvariantRecord>> ecs = new ExecutorCompletionService<>(pool);
    final List<Future<List<InvariantRecord>>> allFutures = new ArrayList<>();

    for (ProgramPoint pt : points) {
      allFutures.add(ecs.submit(() -> processPoint(pt, srcRoot, llm, registry)));
    }

    final Map<Path, List<InvariantRecord>> byFile = new ConcurrentHashMap<>();
    int submitted = allFutures.size();
    int received = 0;
    int totalSpecs = 0;

    // Environment-configurable timeouts
    final long totalTimeoutSec =
        Long.parseLong(
            Objects.requireNonNullElse(System.getenv("DP_LLM_TOTAL_TIMEOUT_SEC"), "180")); // 3 min
    final long pollStepMs =
        Long.parseLong(Objects.requireNonNullElse(System.getenv("DP_LLM_POLL_STEP_MS"), "1500"));

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
        List<InvariantRecord> recs = f.get(); // already completed
        received++;
        if (recs == null || recs.isEmpty()) continue;
        totalSpecs += recs.size();
        Path file = srcRoot.resolve(recs.get(0).sourceFile()).normalize();
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

    // Cancel any stragglers so we don't hang
    for (Future<List<InvariantRecord>> f : allFutures) {
      if (!f.isDone()) f.cancel(true);
    }
    pool.shutdownNow(); // interrupt lingering calls

    System.out.println(">>> Proposed invariant expressions (post-parse filter): " + totalSpecs);
    System.out.println(">>> Files to inject: " + byFile.size());

    // show how many ENTRY/EXIT we actually got
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

    // --- Phase 2: Injection (parallel)
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
    System.out.println(">>> Injection done. Updated files: " + injectedFiles);

    // --- Phase 3: compile with javac and run with java
    final Path classesDir = srcRoot.resolve("daikonpp-classes"); // output dir for compiled classes
    final String selfCp =
        System.getProperty("java.class.path"); // include our runtime (InvariantLogger)
    final String fullCompileCp = JavaRunner.joinCp(selfCp, userClasspath);
    final String fullRunCp = JavaRunner.joinCp(selfCp, classesDir.toString(), userClasspath);

    JavaRunner.compileWithAutoFilter(srcRoot, classesDir, fullCompileCp, /*maxPasses*/ 10);
    final Path runLog = srcRoot.resolve("daikonpp-run.log");
    JavaRunner.run(mainClass, fullRunCp, programArgs, runLog);

    long exitLines = 0;
    try {
      exitLines = Files.lines(runLog).filter(s -> s.contains("\"phase\":\"EXIT\"")).count();
    } catch (IOException ioe) {
      // ignore
    }
    System.out.println(">>> Run log — EXIT events: " + exitLines);

    // --- Phase 4: parse run log and generate the results
    final Set<UUID> falsified = LogParser.readFalsifiedIds(runLog);
    final Map<UUID, edu.njit.jerse.daikonplusplus.App.RecordLite> all =
        parseRegistryLite(cfg.registryPath());

    // held = all - falsified
    Map<String, List<edu.njit.jerse.daikonplusplus.App.RecordLite>> heldByMethod = new TreeMap<>();
    for (edu.njit.jerse.daikonplusplus.App.RecordLite r : all.values()) {
      if (!falsified.contains(r.id)) {
        heldByMethod.computeIfAbsent(r.element, __ -> new ArrayList<>()).add(r);
      }
    }

    System.out.println(">>> HELD invariants by method (ENTRY & EXIT):");
    for (var e : heldByMethod.entrySet()) {
      System.out.println("  - " + e.getKey());
      for (var r : e.getValue()) {
        System.out.println("      [" + r.kind + "] " + r.id + " :: " + r.expr);
      }
    }

    System.out.println(">>> Registry: " + cfg.registryPath().toAbsolutePath());
    System.out.println(">>> Run log: " + runLog.toAbsolutePath());

    if (!CFG.keepWork()) {
      try {
        deleteTree(srcRoot);
        System.out.println(">>> Cleaned working copy: " + srcRoot);
      } catch (IOException ioe) {
        System.err.println("Warning: failed to delete working copy: " + ioe.getMessage());
      }
    } else {
      System.out.println(">>> Keeping working copy (DP_KEEP_WORK=1): " + srcRoot);
    }
  }

  // ----- helpers -----

  private static List<InvariantRecord> processPoint(
      ProgramPoint point,
      Path srcRoot,
      OpenAIInvariantGeneratorClient llm,
      InvariantRegistry registry) {
    try {
      Map<String, String> inScope = extractScope(point, srcRoot);
      Optional<String> body = extractMethodBodyRaw(point, srcRoot);
      List<InvariantSpec> specs = llm.proposeInvariants(point, inScope, body.orElse(null));

      if (specs.isEmpty()) return List.of();

      List<InvariantRecord> out = new ArrayList<>(specs.size());
      java.time.Instant now = java.time.Instant.now();
      String fileRel = point.elementId().filePath();

      for (InvariantSpec spec : specs) {
        // run-level dedup
        String key = keyFor(point, spec.expression());
        if (!RUN_DEDUP.add(key)) continue; // skip duplicates within this run

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

  private static Map<String, String> extractMethodEntryScope(ProgramPoint point, Path srcRoot)
      throws IOException {
    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      Optional<MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(m -> MethodSignatureUtil.jvmDescriptorBestEffort(m).equals(targetDesc))
              .findFirst();
      if (maybe.isPresent()) {
        MethodDeclaration md = maybe.get();
        return md.getParameters().stream()
            .collect(
                Collectors.toMap(
                    p -> p.getName().asString(),
                    p -> p.getType().toString(),
                    (a, b) -> a,
                    LinkedHashMap::new));
      }
    }
    return Map.of();
  }

  /** registry view for reporting without full object re-hydration. */
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

  private static Map<String, String> extractScope(ProgramPoint point, Path srcRoot)
      throws IOException {
    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      Optional<MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(m -> MethodSignatureUtil.jvmDescriptorBestEffort(m).equals(targetDesc))
              .findFirst();
      if (maybe.isPresent()) {
        MethodDeclaration md = maybe.get();
        LinkedHashMap<String, String> scope =
            md.getParameters().stream()
                .collect(
                    Collectors.toMap(
                        p -> p.getName().asString(),
                        p -> p.getType().toString(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        if (point.kind() == ProgramPointKind.METHOD_EXIT) {
          String ret = md.getType().toString();
          if (!"void".equals(ret)) scope.put("result", ret); // LLM can reference 'result'
        }
        return scope;
      }
    }
    return Map.of();
  }

  /** Create a fresh working copy of the user's src tree under build/daikonpp_work/<stamp>. */
  private static Path prepareWorkingCopy(Path userSrcRoot) throws IOException {
    String base = System.getenv().getOrDefault("DP_WORKDIR", "build/daikonpp_work");
    Path baseDir = Path.of(base).toAbsolutePath().normalize();
    Files.createDirectories(baseDir);
    String stamp =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now());
    Path workRoot = baseDir.resolve("src-" + stamp);
    copyTree(userSrcRoot, workRoot);
    System.out.println(">>> Working copy created at: " + workRoot);
    return workRoot;
  }

  /** Recursively copy one tree to another */
  private static void copyTree(Path from, Path to) throws IOException {
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

  /** Delete a directory tree. Set DP_KEEP_WORK=1 to skip cleanup. */
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

  private static Optional<String> extractMethodBodyAbridged(ProgramPoint point, Path srcRoot)
      throws IOException {
    if (!CFG.includeBody()) return Optional.empty();

    int maxChars = 2000;
    try {
      maxChars =
          Math.max(
              200,
              Integer.parseInt(
                  java.util.Objects.requireNonNullElse(
                      System.getenv("DP_BODY_MAX_CHARS"), "2000")));
    } catch (NumberFormatException ignore) {
    }

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    com.github.javaparser.ast.CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls :
        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)) {
      java.util.Optional<com.github.javaparser.ast.body.MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(
                  m ->
                      edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil
                          .jvmDescriptorBestEffort(m)
                          .equals(targetDesc))
              .findFirst();
      if (maybe.isPresent()) {
        String raw = maybe.get().getBody().get().toString(); // includes braces
        // strip comments & squeeze whitespace; keep it short for tokens
        String noBlock = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        String squeezed = noBlock.replaceAll("\\s+", " ").trim();
        if (squeezed.length() > maxChars) {
          squeezed = squeezed.substring(0, maxChars) + " …";
        }
        return Optional.of(squeezed);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> extractMethodBodyRaw(ProgramPoint point, Path srcRoot)
      throws IOException {
    if (!CFG.includeBody()) return Optional.empty();

    Path file = srcRoot.resolve(point.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    final String targetDesc = point.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      Optional<MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(
                  m ->
                      edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil
                          .jvmDescriptorBestEffort(m)
                          .equals(targetDesc))
              .findFirst();
      if (maybe.isPresent()) {
        // tokenRange -> original tokens, including comments & whitespace
        return maybe
            .get()
            .getBody()
            .get()
            .getTokenRange()
            .map(tr -> Optional.of(tr.toString()))
            .orElseGet(() -> Optional.of(maybe.get().getBody().get().toString()));
      }
    }
    return Optional.empty();
  }
}
