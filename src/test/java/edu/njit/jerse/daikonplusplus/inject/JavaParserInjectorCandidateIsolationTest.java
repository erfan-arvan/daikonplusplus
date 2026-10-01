package edu.njit.jerse.daikonplusplus.inject;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.model.InvariantRecord;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramElementId;
import edu.njit.jerse.daikonplusplus.model.ProgramPointImpl;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test: one candidate whose guard cannot be generated must not discard the other
 * candidates of the same file, and must leave no trace in the injected source.
 */
public class JavaParserInjectorCandidateIsolationTest {

  @TempDir Path tmp;

  private static final UUID VALID_ENTRY = UUID.randomUUID();
  private static final UUID COMMENTED_ENTRY = UUID.randomUUID();
  private static final UUID BROKEN_ENTRY = UUID.randomUUID();
  private static final UUID VALID_EXIT = UUID.randomUUID();
  private static final UUID BROKEN_EXIT = UUID.randomUUID();

  private static InvariantRecord rec(UUID id, String desc, ProgramPointKind kind, String expr) {
    ProgramElementId peid =
        ProgramElementId.forMethod("sample", "Calc", "", "sample/Calc.java", desc);
    return new InvariantRecord(
        id,
        new InvariantSpec(expr, "", Map.of()),
        new ProgramPointImpl(peid, kind),
        "sample/Calc.java",
        Instant.now());
  }

  @Test
  public void failingCandidateIsSkippedWhileOthersInSameFileAreInjectedAndEvaluated()
      throws Exception {
    Path srcDir = tmp.resolve("src");
    Path pkg = srcDir.resolve("sample");
    Files.createDirectories(pkg);
    DpRuntimeWriter.write(srcDir);

    Path calc = pkg.resolve("Calc.java");
    Files.writeString(
        calc,
        "package sample;\n"
            + "public class Calc {\n"
            + "  public static void touch(int n) {\n"
            + "  }\n"
            + "  public static int abs(int n) {\n"
            + "    if (n < 0) {\n"
            + "      return -n;\n"
            + "    }\n"
            + "    return n;\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    List<InvariantRecord> recs =
        List.of(
            rec(VALID_ENTRY, "touch(int):void", ProgramPointKind.METHOD_ENTRY, "n >= 0"),
            // Accepted by the relaxed filter: parses as an expression with a trailing comment.
            rec(
                COMMENTED_ENTRY,
                "touch(int):void",
                ProgramPointKind.METHOD_ENTRY,
                "n < 100   // upper bound used by callers"),
            rec(BROKEN_ENTRY, "touch(int):void", ProgramPointKind.METHOD_ENTRY, "n > > 0"),
            rec(VALID_EXIT, "abs(int):int", ProgramPointKind.METHOD_EXIT, "result >= 0"),
            // Must be skipped at both return statements of abs.
            rec(BROKEN_EXIT, "abs(int):int", ProgramPointKind.METHOD_EXIT, "result >= (0"));

    PrintStream origErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Set<UUID> failed;
    try {
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      failed = new JavaParserInjector(new FileWriteCoordinator()).injectGuards(calc, recs);
    } finally {
      System.setErr(origErr);
    }

    // Only the unparseable candidates are reported as failed to inject.
    assertEquals(Set.of(BROKEN_ENTRY, BROKEN_EXIT), failed);

    // The failure log identifies the candidate and shows the generated guard.
    String log = err.toString(StandardCharsets.UTF_8);
    for (String needle :
        List.of(
            BROKEN_ENTRY.toString(),
            "touch(int):void",
            "METHOD_ENTRY",
            "n > > 0",
            BROKEN_EXIT.toString(),
            "METHOD_EXIT",
            "generated guard:",
            "__dp_ok = (n > > 0);")) {
      assertTrue(log.contains(needle), "missing '" + needle + "' in log:\n" + log);
    }

    // No partial changes: failed candidates appear nowhere; valid ones are injected.
    String injected = Files.readString(calc, StandardCharsets.UTF_8);
    assertFalse(injected.contains(BROKEN_ENTRY.toString()));
    assertFalse(injected.contains(BROKEN_EXIT.toString()));
    assertTrue(injected.contains(VALID_ENTRY.toString()));
    assertTrue(injected.contains(COMMENTED_ENTRY.toString()));
    assertTrue(injected.contains(VALID_EXIT.toString()));

    // Compile and run: the valid candidates (including the commented one) are evaluated.
    Path main = srcDir.resolve("Main.java");
    Files.writeString(
        main,
        "public class Main {\n"
            + "  public static void main(String[] a) {\n"
            + "    sample.Calc.touch(5);\n"
            + "    sample.Calc.abs(-3);\n"
            + "    sample.Calc.abs(4);\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);
    Path classes = tmp.resolve("classes");
    Files.createDirectories(classes);
    Process javac =
        new ProcessBuilder(
                "javac",
                "-d",
                classes.toString(),
                srcDir.resolve("daikonpp").resolve("DpRuntime.java").toString(),
                calc.toString(),
                main.toString())
            .redirectErrorStream(true)
            .start();
    String javacOut = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, javac.waitFor(), "injected file must compile:\n" + javacOut);

    Path shm = tmp.resolve("shm");
    Process java =
        new ProcessBuilder(
                "java", "-DDP_SHM_DIR=" + shm.toAbsolutePath(), "-cp", classes.toString(), "Main")
            .redirectErrorStream(true)
            .start();
    String runOut = new String(java.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, java.waitFor(), runOut);

    for (UUID id : List.of(VALID_ENTRY, COMMENTED_ENTRY, VALID_EXIT)) {
      assertTrue(Files.exists(shm.resolve("ex").resolve(id.toString())), id + " not evaluated");
      assertFalse(Files.exists(shm.resolve("fail").resolve(id + ".json")), id + " falsified");
    }
    for (UUID id : List.of(BROKEN_ENTRY, BROKEN_EXIT)) {
      assertFalse(Files.exists(shm.resolve("ex").resolve(id.toString())), id + " was evaluated");
    }
  }
}
