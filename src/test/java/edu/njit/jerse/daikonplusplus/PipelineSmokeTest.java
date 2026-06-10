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
 * Smoke test that replays the baseline registry.jsonl and then runs the pipeline (inject -> javac
 * -> java). It only checks that the pipeline completes successfully and that at least one invariant
 * ID is executed.
 *
 * <p>This is intentionally NOT a byte-for-byte regression test.
 */
public class PipelineSmokeTest {

  private static final Path BASELINE_DIR = Paths.get("testdata", "daikonpp_runs", "baseline");
  private static final Path BASELINE_REGISTRY = BASELINE_DIR.resolve("registry.jsonl");

  private static final Path SAMPLE_SRC = Paths.get("sample-project", "src");
  private static final String MAIN_CLASS = "sample.Main";

  @TempDir Path tmp;

  @Test
  public void pipelineRunsEndToEnd_smoke() throws Exception {
    assertTrue(Files.exists(BASELINE_DIR), "Missing baseline dir: " + BASELINE_DIR);
    assertTrue(Files.exists(BASELINE_REGISTRY), "Missing baseline registry.jsonl");
    assertTrue(Files.exists(SAMPLE_SRC), "Missing sample sources at " + SAMPLE_SRC);

    // 1) Prepare a clean working copy
    Path workSrc = tmp.resolve("src");
    copyTree(SAMPLE_SRC, workSrc);

    // 2) Scan program points
    JavaProjectScanner scanner = new JavaProjectScanner();
    List<ProgramPoint> points = scanner.scanMethodEntryExit(workSrc);

    Map<String, ProgramPoint> elementToPt = new HashMap<>();
    for (ProgramPoint pt : points) {
      String friendly = friendlyElementString(pt, workSrc);
      elementToPt.put(pt.kind().name() + "|" + friendly, pt);
    }

    // 3) Load baseline registry
    List<BaselineRow> baselineRows = readBaselineRegistry(BASELINE_REGISTRY);
    assertFalse(baselineRows.isEmpty(), "Baseline registry is empty");

    Map<Path, List<InvariantRecord>> byFile = new HashMap<>();
    Instant now = Instant.now();

    for (BaselineRow row : baselineRows) {
      ProgramPoint pt = elementToPt.get(row.kind + "|" + row.element);
      assertNotNull(pt, "No matching ProgramPoint for: " + row.kind + "|" + row.element);

      String fileRel = pt.elementId().filePath();

      InvariantSpec spec = new InvariantSpec(row.expr, "", Map.of());
      InvariantRecord rec = new InvariantRecord(row.id, spec, pt, fileRel, now);

      Path file = workSrc.resolve(fileRel).normalize();
      byFile.computeIfAbsent(file, __ -> new ArrayList<>()).add(rec);
    }

    // 4) Inject
    JavaParserInjector injector = new JavaParserInjector(new FileWriteCoordinator());
    for (Map.Entry<Path, List<InvariantRecord>> e : byFile.entrySet()) {
      injector.injectGuards(e.getKey(), e.getValue());
    }

    // 4b) Write DpRuntime helper so injected guards can compile
    DpRuntimeWriter.write(workSrc);

    // 5) Compile & run
    Path classesDir = tmp.resolve("classes");
    Files.createDirectories(classesDir);
    String cp = System.getProperty("java.class.path");

    JavaRunner.compileWithAutoFilter(workSrc, workSrc, classesDir, cp, 10);

    Path runLog = workSrc.resolve("daikonpp-run.log");
    JavaRunner.run(MAIN_CLASS, JavaRunner.joinCp(cp, classesDir.toString()), List.of(), runLog);

    assertTrue(Files.exists(runLog), "Run log was not created at " + runLog);

    Set<UUID> executed = LogParser.readExecutedIds(runLog);
    assertFalse(executed.isEmpty(), "Smoke test: no executed invariant IDs found");
  }

  // ---------------- helpers ----------------

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
          Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
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
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + ".").orElse("");
        String clsName = pkg + cls.getNameAsString();
        String args =
            md.getParameters().stream()
                .map(p -> p.getType().toString())
                .collect(Collectors.joining(","));
        String ret = md.getType().toString();
        return clsName + "#" + md.getNameAsString() + "(" + args + "):" + ret;
      }
    }
    return pt.elementId().toString();
  }

  private static final class BaselineRow {
    final UUID id;
    final String expr;
    final String kind;
    final String element;

    BaselineRow(UUID id, String expr, String kind, String element) {
      this.id = id;
      this.expr = expr;
      this.kind = kind;
      this.element = element;
    }
  }
}
