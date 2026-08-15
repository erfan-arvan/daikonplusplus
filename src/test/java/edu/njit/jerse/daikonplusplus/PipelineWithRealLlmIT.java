package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import edu.njit.jerse.daikonplusplus.llm.LlmInvariantGenerator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration test that runs the full Daikon++ pipeline using the real OpenAI API
 * through {@link LlmInvariantGenerator}. The test exercises all major phases, including LLM
 * invariant generation, injection, compilation, execution, and reporting.
 *
 * <p>This test is disabled by default. To enable it, set the following environment variables:
 *
 * <ul>
 *   <li>{@code DP_E2E_REAL_LLM=1}
 *   <li>{@code OPENAI_API_KEY=&lt;your OpenAI API key&gt;}
 * </ul>
 *
 * <p>Example:
 *
 * <pre>{@code
 * DP_E2E_REAL_LLM=1 OPENAI_API_KEY=sk-... \
 * ./gradlew test --tests "edu.njit.jerse.daikonplusplus.PipelineWithRealLlmIT"
 * }</pre>
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>Default timeouts are used unless overridden (e.g., {@code DP_LLM_TOTAL_TIMEOUT_SEC=60}).
 *   <li>The working copy is deleted after execution unless {@code DP_KEEP_WORK=1} is set.
 * </ul>
 */
final class PipelineWithRealLlmIT {

  private PrintStream origOut;
  private PrintStream origErr;
  private ByteArrayOutputStream outBuf;
  private ByteArrayOutputStream errBuf;

  private void captureStd() {
    origOut = System.out;
    origErr = System.err;
    outBuf = new ByteArrayOutputStream(64 * 1024);
    errBuf = new ByteArrayOutputStream(64 * 1024);
    System.setOut(new PrintStream(outBuf, true));
    System.setErr(new PrintStream(errBuf, true));
  }

  @AfterEach
  void restoreStd() {
    if (origOut != null) System.setOut(origOut);
    if (origErr != null) System.setErr(origErr);
  }

  @Test
  @Timeout(300) // hard cap: 5 minutes to allow for real API + compile + run
  void full_pipeline_calls_real_llm_and_finishes(@TempDir Path tmp) throws Exception {
    // Skip unless explicit opt-in and API key present
    assumeTrue(
        "1".equals(System.getenv("DP_E2E_REAL_LLM")),
        "Set DP_E2E_REAL_LLM=1 to run the real-LLM pipeline IT");
    assumeTrue(
        System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank(),
        "OPENAI_API_KEY must be set to run this test");

    // 1) Create a tiny Java project
    Path srcRoot = tmp.resolve("proj/src");
    Path pkg = srcRoot.resolve("com/example");
    Files.createDirectories(pkg);

    String code =
        """
        package com.example;
        public class Main {
          public static void main(String[] args) {
            int s = 0;
            for (int i = 0; i < 3; i++) s += i;
            System.out.println("sum=" + s);
          }
        }
        """;
    Files.writeString(pkg.resolve("Main.java"), code);

    // 2) Build CLI args — keep it minimal
    String classpath = ""; // nothing extra needed
    String mainClass = "com.example.Main";
    String[] args = {srcRoot.toString(), classpath, mainClass, "1"}; // maxK=1

    // (Optional but recommended) If you want shorter LLM wait:
    // export DP_LLM_TOTAL_TIMEOUT_SEC=60
    // export DP_LLM_POLL_STEP_MS=1000
    // If you want to inspect work dir after run:
    // export DP_KEEP_WORK=1

    // 3) Capture stdout/stderr so we can assert on the pipeline trace
    captureStd();

    // 4) Run the real pipeline with LLM phase enabled
    App.main(args);

    // 5) Analyze output to confirm phases executed
    String out = outBuf.toString();
    String err = errBuf.toString();

    // Basic sanity: no fatal stacktrace in stderr
    assertFalse(err.contains("Exception"), "stderr contains an exception:\n" + err);

    // Confirm key phases logged to stdout (strings from your App)
    assertTrue(out.contains(">>> Scanning sources under"), "Did not scan sources:\n" + out);
    assertTrue(out.contains(">>> Points — ENTRY:"), "Did not print points:\n" + out);
    // LLM phase traces
    assertTrue(
        out.contains("waiting on LLM tasks")
            || out.contains("LLM task failed")
            || out.contains(">>> Proposed invariant expressions"),
        "LLM phase did not appear to run:\n" + out);

    // Injection phase (may be zero if LLM returns nothing)
    assertTrue(out.contains(">>> Files to inject:"), "Missing 'Files to inject' log:\n" + out);
    assertTrue(out.contains(">>> Injection done."), "Missing 'Injection done' log:\n" + out);

    // Compile + run phase
    assertTrue(out.contains(">>> Compiling with javac (pass"), "Missing compile log:\n" + out);
    assertTrue(out.contains(">>> javac OK on pass"), "Compilation did not succeed:\n" + out);
    assertTrue(out.contains(">>> Run log — EXIT events:"), "Missing run log summary:\n" + out);

    // Final totals
    assertTrue(out.contains(">>> Totals: all="), "Missing totals summary:\n" + out);

    // (Optional) Read the working copy outputs if DP_KEEP_WORK=1 is set
    if ("1".equals(System.getenv("DP_KEEP_WORK"))) {
      Path workBase = Paths.get("build/daikonpp_work");
      assertTrue(Files.exists(workBase), "Working directory should exist");
      Path workRoot =
          Files.list(workBase)
              .filter(p -> p.getFileName().toString().startsWith("src-"))
              .sorted()
              .reduce((a, b) -> b)
              .orElseThrow();
      Path classesDir = workRoot.resolve("daikonpp-classes");
      Path runLog = workRoot.resolve("daikonpp-run.log");
      assertTrue(Files.isDirectory(classesDir), "Compiled classes directory should exist");
      assertTrue(Files.exists(runLog), "Run log should exist");
      List<String> lines = Files.readAllLines(runLog);
      assertNotNull(lines);
    }
  }
}
