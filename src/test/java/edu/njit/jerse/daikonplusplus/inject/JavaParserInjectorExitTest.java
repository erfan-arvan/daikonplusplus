package edu.njit.jerse.daikonplusplus.inject;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import edu.njit.jerse.daikonplusplus.model.InvariantRecord;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for METHOD_EXIT injection into void methods.
 *
 * <p>Tail guards must be appended only when the method body can complete normally; otherwise they
 * are unreachable and the injected file does not compile. Guards before explicit {@code return;}
 * statements must be kept, and exceptional exits must stay unchecked.
 */
public class JavaParserInjectorExitTest {

  @TempDir Path tmp;

  /** Method name -> body. Every method is {@code static void name(int n)}. */
  private static final Map<String, String> METHODS = new LinkedHashMap<>();

  static {
    METHODS.put("endsWithReturn", "n++;\n return;");
    METHODS.put("earlyReturnThenFallThrough", "if (n > 0) {\n return;\n }\n n++;");
    METHODS.put("ifElseBothReturn", "if (n > 0) {\n return;\n } else {\n return;\n }");
    METHODS.put("endsWithThrow", "if (n > 0) {\n return;\n }\n throw new IllegalStateException();");
    METHODS.put("infiniteWhile", "while (true) {\n if (n++ > 3) {\n return;\n }\n }");
    METHODS.put("infiniteFor", "for (;;) {\n if (n++ > 3) {\n return;\n }\n }");
    METHODS.put("infiniteWhileWithBreak", "while (true) {\n if (n++ > 3) {\n break;\n }\n }");
    METHODS.put(
        "labeledBreakOutOfInfiniteLoop",
        "outer:\n while (true) {\n while (true) {\n if (n++ > 3) {\n break outer;\n }\n }\n }");
    METHODS.put("tryReturnFinally", "try {\n return;\n } finally {\n n++;\n }");
    METHODS.put(
        "tryCatchFallThrough", "try {\n n++;\n } catch (RuntimeException ex) {\n return;\n }");
    METHODS.put(
        "switchAllReturnWithDefault", "switch (n) {\n case 1:\n return;\n default:\n return;\n }");
    METHODS.put("switchWithoutDefault", "switch (n) {\n case 1:\n return;\n }");
    METHODS.put("synchronizedReturn", "synchronized (LOCK) {\n return;\n }");
    METHODS.put("emptyBody", "");
  }

  /** Methods whose end is reachable and therefore must get tail guards. */
  private static final List<String> END_REACHABLE =
      List.of(
          "earlyReturnThenFallThrough",
          "infiniteWhileWithBreak",
          "labeledBreakOutOfInfiniteLoop",
          "tryCatchFallThrough",
          "switchWithoutDefault",
          "emptyBody");

  private final Map<String, UUID> ids = new LinkedHashMap<>();

  private Path srcDir;
  private Path classFile;

  /** Writes the sample class plus DpRuntime and injects one EXIT invariant per method. */
  private String injectAll(String expr) throws Exception {
    srcDir = tmp.resolve("src");
    Path pkgDir = srcDir.resolve("sample");
    Files.createDirectories(pkgDir);
    DpRuntimeWriter.write(srcDir);

    StringBuilder cls = new StringBuilder("package sample;\npublic class Exits {\n");
    cls.append("  private static final Object LOCK = new Object();\n");
    for (Map.Entry<String, String> m : METHODS.entrySet()) {
      cls.append("  public static void ")
          .append(m.getKey())
          .append("(int n) {\n")
          .append(m.getValue())
          .append("\n  }\n");
    }
    cls.append(
        "  public static int nonVoid(int n) {\n if (n > 0) {\n return n;\n }\n return -n;\n }\n");
    cls.append("}\n");
    classFile = pkgDir.resolve("Exits.java");
    Files.writeString(classFile, cls.toString(), StandardCharsets.UTF_8);

    List<InvariantRecord> recs = new ArrayList<>();
    List<String> names = new ArrayList<>(METHODS.keySet());
    names.add("nonVoid");
    for (String name : names) {
      String desc = name.equals("nonVoid") ? "nonVoid(int):int" : name + "(int):void";
      UUID id = UUID.randomUUID();
      ids.put(name, id);
      ProgramElementId peid =
          ProgramElementId.forMethod("sample", "Exits", "", "sample/Exits.java", desc);
      recs.add(
          new InvariantRecord(
              id,
              new InvariantSpec(expr, "", Map.of()),
              new ProgramPointImpl(peid, ProgramPointKind.METHOD_EXIT),
              "sample/Exits.java",
              Instant.now()));
    }
    new JavaParserInjector(new FileWriteCoordinator()).injectGuards(classFile, recs);
    return Files.readString(classFile, StandardCharsets.UTF_8);
  }

  private Path compile(Path mainFile) throws Exception {
    Path classes = tmp.resolve("classes");
    Files.createDirectories(classes);
    List<String> cmd = new ArrayList<>();
    cmd.add("javac");
    cmd.add("-d");
    cmd.add(classes.toString());
    cmd.add(srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString());
    cmd.add(classFile.toString());
    if (mainFile != null) {
      cmd.add(mainFile.toString());
    }
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), "injected code must compile:\n" + out);
    return classes;
  }

  /** Runs {@code body} as a main method in a fresh JVM with its own shm directory. */
  private Path run(String body) throws Exception {
    Path mainFile = srcDir.resolve("Main.java");
    Files.writeString(
        mainFile,
        "public class Main {\n  public static void main(String[] a) {\n" + body + "\n  }\n}\n",
        StandardCharsets.UTF_8);
    Path classes = compile(mainFile);
    Path shm = Files.createTempDirectory(tmp, "shm");
    ProcessBuilder pb =
        new ProcessBuilder(
                "java", "-DDP_SHM_DIR=" + shm.toAbsolutePath(), "-cp", classes.toString(), "Main")
            .redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), "sample run failed:\n" + out);
    return shm;
  }

  private static boolean executed(Path shm, UUID id) {
    return Files.exists(shm.resolve("ex").resolve(id.toString()));
  }

  private static boolean falsified(Path shm, UUID id) {
    return Files.exists(shm.resolve("fail").resolve(id + ".json"));
  }

  private static String methodSource(String injected, String name) {
    CompilationUnit cu = StaticJavaParser.parse(injected);
    MethodDeclaration md =
        cu.findAll(MethodDeclaration.class).stream()
            .filter(m -> m.getNameAsString().equals(name))
            .findFirst()
            .orElseThrow();
    return md.toString();
  }

  private static int count(String haystack, String needle) {
    int c = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      c++;
    }
    return c;
  }

  @Test
  public void injectedVoidExitsCompileForAllControlFlowShapes() throws Exception {
    injectAll("n >= 0 || n < 0");
    compile(null);
  }

  @Test
  public void tailGuardsOnlyWhenBodyCanCompleteNormally() throws Exception {
    String injected = injectAll("n >= 0 || n < 0");
    for (String name : METHODS.keySet()) {
      String src = methodSource(injected, name);
      int tails = count(src, "_tail");
      if (END_REACHABLE.contains(name)) {
        assertTrue(tails > 0, name + " can complete normally, expected a tail guard:\n" + src);
      } else {
        assertEquals(0, tails, name + " cannot complete normally, tail guard is unreachable");
      }
    }
    // Non-void methods never get tail guards; each return is still guarded.
    String nonVoid = methodSource(injected, "nonVoid");
    assertEquals(0, count(nonVoid, "_tail"));
    assertEquals(2, count(nonVoid, "__DP_INVARIANT_BEGIN__"));
  }

  @Test
  public void guardsBeforeExplicitReturnsArePreserved() throws Exception {
    String injected = injectAll("n >= 0 || n < 0");
    for (String name : METHODS.keySet()) {
      String body = METHODS.get(name);
      int returns = count(body, "return;");
      String src = methodSource(injected, name);
      int tail = END_REACHABLE.contains(name) ? 1 : 0;
      assertEquals(
          returns + tail,
          count(src, "__DP_INVARIANT_BEGIN__"),
          name + ": one guard per explicit return; plus the tail guard if the end is reachable");
    }
  }

  @Test
  public void explicitReturnGuardRunsWhenReturningEarly() throws Exception {
    injectAll("n < 0");
    Path shm = run("sample.Exits.earlyReturnThenFallThrough(5);\nsample.Exits.endsWithReturn(-9);");
    // n = 5 exits through the explicit 'return;' -> guard runs and n < 0 is violated.
    assertTrue(executed(shm, ids.get("earlyReturnThenFallThrough")));
    assertTrue(falsified(shm, ids.get("earlyReturnThenFallThrough")));
    // Method ending in 'return;' is still checked there (n = -8 < 0 holds).
    assertTrue(executed(shm, ids.get("endsWithReturn")));
    assertFalse(falsified(shm, ids.get("endsWithReturn")));
  }

  @Test
  public void tailGuardRunsWhenFallingThrough() throws Exception {
    injectAll("n < 0");
    Path shm =
        run(
            "sample.Exits.earlyReturnThenFallThrough(-5);\n"
                + "sample.Exits.infiniteWhileWithBreak(-5);\n"
                + "sample.Exits.labeledBreakOutOfInfiniteLoop(-5);");
    // n = -5 falls through to the end of the body (n becomes -4): tail guard holds.
    assertTrue(executed(shm, ids.get("earlyReturnThenFallThrough")));
    assertFalse(falsified(shm, ids.get("earlyReturnThenFallThrough")));
    // Loops exit via break once n > 3, so the tail guard sees n = 5 and n < 0 fails.
    assertTrue(falsified(shm, ids.get("infiniteWhileWithBreak")));
    assertTrue(falsified(shm, ids.get("labeledBreakOutOfInfiniteLoop")));
  }

  @Test
  public void exceptionalExitIsNotChecked() throws Exception {
    injectAll("n < 0");
    Path shm =
        run(
            "try {\n sample.Exits.endsWithThrow(-1);\n} catch (IllegalStateException expected) {\n}");
    assertFalse(executed(shm, ids.get("endsWithThrow")), "throw exits must not run EXIT guards");
  }
}
