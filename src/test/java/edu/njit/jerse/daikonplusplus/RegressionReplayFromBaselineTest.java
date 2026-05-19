package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import edu.njit.jerse.daikonplusplus.inject.DpRuntimeWriter;
import edu.njit.jerse.daikonplusplus.inject.FileWriteCoordinator;
import edu.njit.jerse.daikonplusplus.inject.JavaParserInjector;
import edu.njit.jerse.daikonplusplus.model.*;
import edu.njit.jerse.daikonplusplus.parse.JavaProjectScanner;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test that replays the baseline registry.jsonl (stored in the repo) and then runs the
 * rest of the pipeline (inject -> javac -> java). It compares the new artifacts against the
 * committed baseline artifacts byte-for-byte.
 *
 * <p>Baseline folder (committed): testdata/daikonpp_runs/baseline/ - registry.jsonl (LLM outputs
 * captured previously) - injected-src/ (injected sources from baseline run) - daikonpp-run.log
 * (runtime log from baseline run) - app-console.log (not required for assertions) - workcopy/ (not
 * required for assertions)
 *
 * <p>Sample project sources expected at: sample-project/src/ - sample/Main.java -
 * sample/MathUtils.java
 */
public class RegressionReplayFromBaselineTest {

  private static final Path BASELINE_DIR = Paths.get("testdata/daikonpp_runs/baseline");
  private static final Path BASELINE_REGISTRY = BASELINE_DIR.resolve("registry.jsonl");
  private static final Path BASELINE_INJECTED = BASELINE_DIR.resolve("injected-src");
  private static final Path BASELINE_RUNLOG = BASELINE_DIR.resolve("daikonpp-run.log");

  private static final Path SAMPLE_SRC = Paths.get("sample-project", "src");
  private static final String MAIN_CLASS = "sample.Main";

  @TempDir Path tmp;

  @Test
  public void replayBaselineRegistry_thenPipelineArtifactsMatchBaseline_exactly() throws Exception {
    assertTrue(Files.exists(BASELINE_DIR), "Missing baseline dir: " + BASELINE_DIR);
    assertTrue(Files.exists(BASELINE_REGISTRY), "Missing baseline registry.jsonl");
    assertTrue(Files.isDirectory(BASELINE_INJECTED), "Missing baseline injected-src/");
    assertTrue(Files.exists(SAMPLE_SRC), "Missing sample sources at " + SAMPLE_SRC);

    // 1) Prepare a clean working copy of the sample sources
    Path workSrc = tmp.resolve("src");
    copyTree(SAMPLE_SRC, workSrc);

    // 2) Scan program points from the working copy
    JavaProjectScanner scanner = new JavaProjectScanner();
    List<ProgramPoint> points = scanner.scanMethodEntryExit(workSrc);

    // Map a stable, human-readable element string -> ProgramPoint (ENTRY/EXIT)
    Map<String, ProgramPoint> elementToPt = new HashMap<>();
    for (ProgramPoint pt : points) {
      String friendly = friendlyElementString(pt, workSrc);
      elementToPt.put(pt.kind().name() + "|" + friendly, pt);
    }

    // 3) Load baseline registry.jsonl and reconstruct InvariantRecords with IDENTICAL UUIDs
    List<BaselineRow> baselineRows = readBaselineRegistry(BASELINE_REGISTRY);
    assertFalse(baselineRows.isEmpty(), "Baseline registry is empty");

    // Build records grouped by file for injection
    Map<Path, List<InvariantRecord>> byFile = new HashMap<>();
    Instant now = Instant.now();

    for (BaselineRow row : baselineRows) {
      ProgramPoint pt = elementToPt.get(row.kind + "|" + row.element);
      assertNotNull(
          pt, "No matching ProgramPoint for baseline element: " + row.kind + "|" + row.element);

      String fileRel = pt.elementId().filePath(); // consistent with injector expectations

      InvariantSpec spec = new InvariantSpec(row.expr, /*rationale*/ "", /*meta*/ Map.of());
      InvariantRecord rec = new InvariantRecord(row.id, spec, pt, fileRel, now);

      Path file = workSrc.resolve(fileRel).normalize();
      byFile.computeIfAbsent(file, __ -> new ArrayList<>()).add(rec);
    }

    // 4) Inject using the real injector
    JavaParserInjector injector = new JavaParserInjector(new FileWriteCoordinator());
    for (Map.Entry<Path, List<InvariantRecord>> e : byFile.entrySet()) {
      injector.injectGuards(e.getKey(), e.getValue());
    }

    // 5) Write DpRuntime helper so injected code can compile
    DpRuntimeWriter.write(workSrc);

    // 6) Compile with javac and run with java to produce a fresh run log
    Path classesDir = tmp.resolve("classes");
    Files.createDirectories(classesDir);
    String cp = System.getProperty("java.class.path");

    JavaRunner.compileWithAutoFilter(workSrc, classesDir, cp, /*maxPasses*/ 10);

    Path runLog = workSrc.resolve("daikonpp-run.log");
    JavaRunner.run(MAIN_CLASS, JavaRunner.joinCp(cp, classesDir.toString()), List.of(), runLog);

    // Sanity: parse logs so the run truly happened (not strictly required for regression)
    Set<UUID> executed = LogParser.readExecutedIds(runLog);
    assertFalse(executed.isEmpty(), "No executed IDs found in fresh run");

    // 6) Compare ARTIFACTS with baseline (byte-for-byte)
    // 6a) Compare injected sources
    compareInjectedTrees(BASELINE_INJECTED, workSrc, "injected sources mismatch vs baseline");

    // 6b) Compare run logs (exact)
    assertTrue(Files.exists(BASELINE_RUNLOG), "Missing baseline run log: " + BASELINE_RUNLOG);
    byte[] baseLog = Files.readAllBytes(BASELINE_RUNLOG);
    byte[] newLog = Files.readAllBytes(runLog);
    if (!Arrays.equals(baseLog, newLog)) {
      // Helpful diff context
      String baseS = new String(baseLog, StandardCharsets.UTF_8);
      String newS = new String(newLog, StandardCharsets.UTF_8);
      fail(
          "Run log differs from baseline.\n--- baseline ---\n"
              + snippet(baseS)
              + "\n--- current ---\n"
              + snippet(newS));
    }
  }

  // -------------------- Helpers --------------------

  private static void copyTree(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    try (var s = Files.walk(from)) {
      for (Path p : s.toList()) {
        Path rel = from.relativize(p);
        Path dest = to.resolve(rel.toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(
              p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static List<BaselineRow> readBaselineRegistry(Path jsonl) throws IOException {
    List<BaselineRow> out = new ArrayList<>();
    for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;
      UUID id = extract(line, "\"id\":\"", "\"").map(UUID::fromString).orElse(null);
      String expr = extract(line, "\"expr\":\"", "\"").orElse(null);
      String kind = extract(line, "\"kind\":\"", "\"").orElse(null);
      String element = extract(line, "\"element\":\"", "\"").orElse(null);
      if (id != null && expr != null && kind != null && element != null) {
        out.add(new BaselineRow(id, expr, kind, element));
      }
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

  /**
   * Build the same human-readable element string the baseline stores, e.g.,
   * "sample.MathUtils#max(int,int):int".
   */
  private static String friendlyElementString(ProgramPoint pt, Path srcRoot) throws IOException {
    Path file = srcRoot.resolve(pt.elementId().filePath()).normalize();
    CompilationUnit cu = StaticJavaParser.parse(file);
    String targetDesc = pt.elementId().jvmDescriptor();

    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
      Optional<MethodDeclaration> maybe =
          cls.getMethods().stream()
              .filter(m -> m.getBody().isPresent())
              .filter(m -> MethodSignatureUtil.jvmDescriptorBestEffort(m).equals(targetDesc))
              .findFirst();
      if (maybe.isPresent()) {
        MethodDeclaration md = maybe.get();
        // package.Class
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + ".").orElse("");
        String clsName = pkg + cls.getNameAsString();
        // methodName(argTypes):retType
        String args =
            md.getParameters().stream()
                .map(p -> p.getType().toString())
                .collect(Collectors.joining(","));
        String ret = md.getType().toString();
        return clsName + "#" + md.getNameAsString() + "(" + args + "):" + ret;
      }
    }
    // Fallback to elementId().toString()
    return pt.elementId().toString();
  }

  /**
   * Compare all injected Java sources under baseline/injected-src against freshly injected content
   * in workSrc.
   */
  private static void compareInjectedTrees(Path baselineInjected, Path workSrc, String msg)
      throws IOException {
    // Collect baseline files
    List<Path> baseFiles;
    try (var s = Files.walk(baselineInjected)) {
      baseFiles = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
    assertFalse(baseFiles.isEmpty(), "No .java found in baseline injected-src");

    for (Path baseFile : baseFiles) {
      // Determine the corresponding path in the fresh work copy
      // Baseline injected-src kept original relative layout under the working copy; replicate
      // mapping:
      Path rel = baselineInjected.relativize(baseFile);
      Path freshFile = workSrc.resolve(rel.toString());

      assertTrue(Files.exists(freshFile), "Fresh injected file missing: " + freshFile);

      byte[] baseBytes = Files.readAllBytes(baseFile);
      byte[] freshBytes = Files.readAllBytes(freshFile);

      if (!Arrays.equals(baseBytes, freshBytes)) {
        String baseS = new String(baseBytes, StandardCharsets.UTF_8);
        String newS = new String(freshBytes, StandardCharsets.UTF_8);
        fail(
            msg
                + " for "
                + rel
                + "\n--- baseline ---\n"
                + snippet(baseS)
                + "\n--- current ---\n"
                + snippet(newS));
      }
    }
  }

  private static String snippet(String s) {
    String[] lines = s.split("\\R");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(lines.length, 80); i++) {
      sb.append(lines[i]).append("\n");
    }
    if (lines.length > 80) sb.append("... (truncated) ...\n");
    return sb.toString();
  }

  private static final class BaselineRow {
    final UUID id;
    final String expr;
    final String kind; // e.g., "METHOD_ENTRY" or "METHOD_EXIT"
    final String element; // e.g., "sample.MathUtils#max(int,int):int"

    BaselineRow(UUID id, String expr, String kind, String element) {
      this.id = id;
      this.expr = expr;
      this.kind = kind;
      this.element = element;
    }
  }
}
