package edu.njit.jerse.daikonplusplus.filter;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.JavaRunner;
import edu.njit.jerse.daikonplusplus.inject.DpRuntimeWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of {@link TestInvariantFilter}'s delta-debugging (ddmin) search against a small,
 * hand-built "external project": three injected invariant checks execute on every run, only one of
 * them is actually false, and only that one throws to fail the run. The other two are decoys —
 * present in the candidate pool, executed, but never the cause of the failure.
 *
 * <p>No LLM/cassette machinery is involved: the invariant blocks are written directly (bypassing
 * proposal + injection), since what's under test is ddmin's search over an already-known candidate
 * pool, not invariant proposal or injection. The project is compiled and run for real via {@link
 * JavaRunner#runExternalScript}, exactly as {@code TestInvariantFilter} itself does for every
 * trial, so the whole exit-code / log-scan / candidate-seeding path is exercised genuinely.
 */
public class TestInvariantFilterDdminTest {

  private static final UUID INV_A_TRUE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID INV_B_TRUE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID INV_C_FALSE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  public void ddminIsolatesTheSingleFalseInvariantAmongDecoys(@TempDir Path tmp) throws Exception {
    Path projectRoot = tmp.resolve("project");
    Path mainSrcRoot = projectRoot.resolve("src");
    Path pkgDir = mainSrcRoot.resolve("com/example");
    Files.createDirectories(pkgDir);

    Files.writeString(pkgDir.resolve("Main.java"), mainJavaSource(), StandardCharsets.UTF_8);
    DpRuntimeWriter.write(mainSrcRoot);

    Path script = projectRoot.resolve("run.sh");
    Files.writeString(script, runnerScript(), StandardCharsets.UTF_8);
    script.toFile().setExecutable(true);

    // ---- Initial run: all three invariants active, C is false, program exits non-zero. ----
    Path initialRunLog = tmp.resolve("initial.log");
    JavaRunner.RunResult initialResult =
        JavaRunner.runExternalScript(
            script,
            projectRoot,
            null,
            initialRunLog,
            /* timeoutMinutes= */ 2,
            /* staleCheckMinutes= */ 0);

    assertEquals(JavaRunner.RunResult.NORMAL, initialResult, "initial run should exit on its own");
    String initialLogText = Files.readString(initialRunLog, StandardCharsets.UTF_8);
    assertTrue(
        initialLogText.contains("AssertionError"),
        "initial run should have failed with the injected AssertionError:\n" + initialLogText);

    // No registry needed for ddmin correctness itself (it's only used for human-readable method
    // labels in logs) — point at a nonexistent path.
    Path registryPath = tmp.resolve("no-such-registry.jsonl");

    TestInvariantFilter.Result result =
        TestInvariantFilter.run(
            projectRoot,
            mainSrcRoot,
            registryPath,
            initialRunLog,
            script,
            /* methodBatchSize= */ 1,
            /* timeoutMinutes= */ 2,
            /* staleCheckMinutes= */ 0,
            Optional.empty(),
            null);

    assertEquals(
        Set.of(INV_C_FALSE),
        result.removedIds,
        "ddmin should isolate exactly the one false invariant, not the two true decoys");
    assertEquals(0, result.finalExitCode, "final rerun with only the culprit disabled should pass");
  }

  private static String mainJavaSource() {
    return "package com.example;\n"
        + "public final class Main {\n"
        + "  public static void main(String[] args) {\n"
        + "    int x = 5;\n"
        + "    // __DP_INVARIANT_BEGIN__ "
        + INV_A_TRUE
        + "\n"
        + "    if (!daikonpp.DpRuntime.DISABLED.contains(\""
        + INV_A_TRUE
        + "\")) {\n"
        + "      daikonpp.DpRuntime.recordExecuted(\""
        + INV_A_TRUE
        + "\");\n"
        + "      if (!(x > 0)) { throw new AssertionError(\"invariant "
        + INV_A_TRUE
        + " violated\"); }\n"
        + "    }\n"
        + "    // __DP_INVARIANT_END__\n"
        + "\n"
        + "    // __DP_INVARIANT_BEGIN__ "
        + INV_B_TRUE
        + "\n"
        + "    if (!daikonpp.DpRuntime.DISABLED.contains(\""
        + INV_B_TRUE
        + "\")) {\n"
        + "      daikonpp.DpRuntime.recordExecuted(\""
        + INV_B_TRUE
        + "\");\n"
        + "      if (!(x < 100)) { throw new AssertionError(\"invariant "
        + INV_B_TRUE
        + " violated\"); }\n"
        + "    }\n"
        + "    // __DP_INVARIANT_END__\n"
        + "\n"
        + "    // __DP_INVARIANT_BEGIN__ "
        + INV_C_FALSE
        + "\n"
        + "    if (!daikonpp.DpRuntime.DISABLED.contains(\""
        + INV_C_FALSE
        + "\")) {\n"
        + "      daikonpp.DpRuntime.recordExecuted(\""
        + INV_C_FALSE
        + "\");\n"
        + "      if (!(x > 1000)) { throw new AssertionError(\"invariant "
        + INV_C_FALSE
        + " violated\"); }\n"
        + "    }\n"
        + "    // __DP_INVARIANT_END__\n"
        + "\n"
        + "    System.out.println(\"done\");\n"
        + "  }\n"
        + "}\n";
  }

  private static String runnerScript() {
    return "#!/usr/bin/env bash\n"
        + "set -uo pipefail\n"
        + "rm -rf classes && mkdir -p classes\n"
        + "SRC_FILES=$(find src -name '*.java')\n"
        + "javac -d classes $SRC_FILES\n"
        + "java -cp classes com.example.Main\n"
        + "exit $?\n";
  }
}
