package edu.njit.jerse.daikonplusplus.inject;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Injection must scale with method size, not file size, and per-method injection must keep the
 * file's formatting (nested members, CRLF line endings) and compile.
 */
public class JavaParserInjectorScalingTest {

  @TempDir Path tmp;

  private static InvariantRecord rec(String cls, String desc, ProgramPointKind kind, String expr) {
    ProgramElementId peid =
        ProgramElementId.forMethod("sample", cls, "", "sample/" + cls + ".java", desc);
    return new InvariantRecord(
        UUID.randomUUID(),
        new InvariantSpec(expr, "", Map.of()),
        new ProgramPointImpl(peid, kind),
        "sample/" + cls + ".java",
        Instant.now());
  }

  @Test
  public void manyCandidatesInOneFileInjectInReasonableTime() throws Exception {
    // 2000 candidates (3000 guards) in one file took several minutes when the whole file was
    // observed by the lexical-preserving printer (quadratic); per-method injection takes seconds.
    int methods = 200;
    StringBuilder sb = new StringBuilder("package sample;\npublic class Big {\n");
    for (int m = 0; m < methods; m++) {
      sb.append("  public int m")
          .append(m)
          .append("(int n) {\n    if (n > 0) {\n      return n;\n    }\n    return -n;\n  }\n");
    }
    sb.append("}\n");
    Path file = tmp.resolve("Big.java");
    Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

    List<InvariantRecord> recs = new ArrayList<>();
    for (int m = 0; m < methods; m++) {
      for (int k = 0; k < 5; k++) {
        recs.add(rec("Big", "m" + m + "(int):int", ProgramPointKind.METHOD_ENTRY, "n > -" + k));
        recs.add(rec("Big", "m" + m + "(int):int", ProgramPointKind.METHOD_EXIT, "result >= " + k));
      }
    }

    long t0 = System.nanoTime();
    Set<UUID> failed = new JavaParserInjector(new FileWriteCoordinator()).injectGuards(file, recs);
    double seconds = (System.nanoTime() - t0) / 1e9;

    assertTrue(failed.isEmpty());
    String out = Files.readString(file, StandardCharsets.UTF_8);
    for (InvariantRecord r : recs) {
      assertTrue(out.contains(r.id().toString()), "missing guard for " + r.id());
    }
    assertTrue(seconds < 120, "injecting 2000 candidates into one file took " + seconds + "s");
  }

  @Test
  public void nestedMethodsAndCrlfAreHandledAndCompile() throws Exception {
    Path srcDir = tmp.resolve("src");
    Path pkg = srcDir.resolve("sample");
    Files.createDirectories(pkg);
    DpRuntimeWriter.write(srcDir);

    String lf =
        "package sample;\n"
            + "public class Nest {\n"
            + "    static int count;\n"
            + "    public static void run(int n) {\n"
            + "        Runnable r = new Runnable() {\n"
            + "            public void run() {\n"
            + "                if (count > 5) {\n"
            + "                    return;\n"
            + "                }\n"
            + "                count++;\n"
            + "            }\n"
            + "        };\n"
            + "        r.run();\n"
            + "    }\n"
            + "    static class Inner {\n"
            + "        static int twice(int k) { return k * 2; }\n"
            + "    }\n"
            + "}\n";
    Path file = pkg.resolve("Nest.java");
    Files.writeString(file, lf.replace("\n", "\r\n"), StandardCharsets.UTF_8);

    List<InvariantRecord> recs =
        List.of(
            rec("Nest", "run(int):void", ProgramPointKind.METHOD_ENTRY, "n >= 0"),
            rec("Nest", "run():void", ProgramPointKind.METHOD_ENTRY, "count >= 0"),
            rec("Nest", "run():void", ProgramPointKind.METHOD_EXIT, "count >= 1"),
            rec("Nest", "twice(int):int", ProgramPointKind.METHOD_EXIT, "result == k * 2"));
    assertTrue(
        new JavaParserInjector(new FileWriteCoordinator()).injectGuards(file, recs).isEmpty());

    String out = Files.readString(file, StandardCharsets.UTF_8);
    for (InvariantRecord r : recs) {
      assertTrue(out.contains(r.id().toString()), "missing guard for " + r.id());
    }
    // Code without candidates is preserved verbatim, including its CRLF line endings.
    assertTrue(out.contains("    static class Inner {\r\n"));
    assertTrue(
        out.startsWith("package sample;\r\npublic class Nest {\r\n    static int count;\r\n"));

    Path classes = tmp.resolve("classes");
    Files.createDirectories(classes);
    Process javac =
        new ProcessBuilder(
                "javac",
                "-d",
                classes.toString(),
                srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString(),
                file.toString())
            .redirectErrorStream(true)
            .start();
    String log = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, javac.waitFor(), "injected file must compile:\n" + log);
  }
}
