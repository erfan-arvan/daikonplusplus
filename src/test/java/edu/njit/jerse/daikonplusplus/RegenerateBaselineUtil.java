package edu.njit.jerse.daikonplusplus;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Regenerates the regression test baselines with the current code. Run with: gradle test
 * -Dregenerate.baseline=true --tests RegenerateBaselineUtil
 */
@EnabledIfSystemProperty(named = "regenerate.baseline", matches = "true")
public class RegenerateBaselineUtil {

  private static final Path BASELINE_DIR = Paths.get("testdata/daikonpp_runs/baseline");
  private static final Path BASELINE_REGISTRY = BASELINE_DIR.resolve("registry.jsonl");
  private static final Path BASELINE_INJECTED = BASELINE_DIR.resolve("injected-src");
  private static final Path SAMPLE_SRC = Paths.get("sample-project", "src");
  private static final String MAIN_CLASS = "sample.Main";

  @Test
  public void regenerateBaseline() throws Exception {
    System.out.println("[REGEN] Regenerating baselines...");

    Path tmpDir = Files.createTempDirectory("dp-regen");
    try {
      Path workSrc = tmpDir.resolve("src");
      copyTree(SAMPLE_SRC, workSrc);
      Path origSrc = tmpDir.resolve("src-orig");
      copyTree(workSrc, origSrc);

      JavaProjectScanner scanner = new JavaProjectScanner();
      List<ProgramPoint> points = scanner.scanMethodEntryExit(workSrc);

      Map<String, ProgramPoint> elementToPt = new HashMap<>();
      for (ProgramPoint pt : points) {
        String friendly = friendlyElementString(pt, workSrc);
        elementToPt.put(pt.kind().name() + "|" + friendly, pt);
      }

      List<BaselineRow> rows = readBaselineRegistry(BASELINE_REGISTRY);
      Map<Path, List<InvariantRecord>> byFile = new HashMap<>();
      Instant now = Instant.now();

      for (BaselineRow row : rows) {
        ProgramPoint pt = elementToPt.get(row.kind + "|" + row.element);
        if (pt == null) {
          System.err.println(
              "[REGEN] WARNING: no ProgramPoint for " + row.kind + "|" + row.element);
          continue;
        }
        String fileRel = pt.elementId().filePath();
        InvariantSpec spec = new InvariantSpec(row.expr, "", Map.of());
        InvariantRecord rec = new InvariantRecord(row.id, spec, pt, fileRel, now);
        Path file = workSrc.resolve(fileRel).normalize();
        byFile.computeIfAbsent(file, __ -> new ArrayList<>()).add(rec);
      }

      JavaParserInjector injector = new JavaParserInjector(new FileWriteCoordinator());
      for (Map.Entry<Path, List<InvariantRecord>> e : byFile.entrySet()) {
        injector.injectGuards(e.getKey(), e.getValue());
      }
      DpRuntimeWriter.write(workSrc);

      // Copy fresh injected sources to baseline/injected-src
      if (Files.exists(BASELINE_INJECTED)) {
        deleteTree(BASELINE_INJECTED);
      }
      copyTree(workSrc, BASELINE_INJECTED);
      // Remove the DpRuntime helper from baseline injected-src (it's generated, not baseline)
      // Keep it since the test compares it too
      System.out.println("[REGEN] Updated baseline/injected-src/");

      // Compile and run to regenerate the run log
      Path classesDir = tmpDir.resolve("classes");
      Files.createDirectories(classesDir);
      String cp = System.getProperty("java.class.path");

      JavaRunner.compileWithAutoFilter(workSrc, origSrc, classesDir, cp, 10);

      Path runLog = workSrc.resolve("daikonpp-run.log");
      JavaRunner.run(MAIN_CLASS, JavaRunner.joinCp(cp, classesDir.toString()), List.of(), runLog);

      // Copy run log to baseline
      Files.copy(
          runLog, BASELINE_DIR.resolve("daikonpp-run.log"), StandardCopyOption.REPLACE_EXISTING);
      System.out.println("[REGEN] Updated baseline/daikonpp-run.log");
      System.out.println("[REGEN] Baseline regeneration complete.");

    } finally {
      deleteTree(tmpDir);
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
                .collect(java.util.stream.Collectors.joining(","));
        String ret = md.getType().toString();
        return clsName + "#" + md.getNameAsString() + "(" + args + "):" + ret;
      }
    }
    return pt.elementId().toString();
  }

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

  record BaselineRow(UUID id, String expr, String kind, String element) {}
}
