package edu.njit.jerse.daikonplusplus;

import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil;
import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil.JError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Autofilter using user-provided external compile scripts.
 *
 * <p>The user provides TWO scripts: - one for MAIN compilation - one for TEST compilation
 *
 * <p>This class: - runs the script - parses javac-style errors - disables invariant regions
 *
 * <p>The script IS the compiler.
 */
public final class ExternalCompileRunner {

  private ExternalCompileRunner() {}

  public static void compileWithAutoFilter(
      Path workProjectRoot,
      Path compileScript, // MAIN or TEST script (chosen by caller)
      int maxPasses)
      throws Exception {

    if (!Files.isExecutable(compileScript)) {
      throw new IllegalArgumentException("[DP] Compile script not executable: " + compileScript);
    }

    Path errLog = workProjectRoot.resolve("dp-external-compile.err");

    for (int pass = 1; pass <= maxPasses; pass++) {

      System.out.println(
          "[DP] === external autofilter pass "
              + pass
              + " (script="
              + compileScript.getFileName()
              + ") ===");

      ProcessBuilder pb = new ProcessBuilder(compileScript.toAbsolutePath().toString());

      Map<String, String> env = pb.environment();
      env.put("DP_PROJECT_ROOT", workProjectRoot.toAbsolutePath().toString());

      pb.directory(workProjectRoot.toFile());
      pb.redirectOutput(errLog.toFile());
      pb.redirectError(errLog.toFile());

      int exit = pb.start().waitFor();
      System.out.println("[DP] external compile exit code: " + exit);

      if (exit == 0) {
        System.out.println("[DP] external compilation succeeded");
        return;
      }

      String err = Files.exists(errLog) ? Files.readString(errLog, StandardCharsets.UTF_8) : "";

      Path seen = workProjectRoot.resolve("dp-autofilter-seen.txt");
      Files.writeString(seen, err, StandardCharsets.UTF_8);
      System.out.println("[DP] wrote autofilter input to " + seen);

      List<JError> errors = InvariantAutoFilterUtil.parseExternalCompilerErrors(err);

      if (errors.isEmpty()) {
        throw new RuntimeException(
            "External build failed but no javac-style errors were found.\n"
                + "Autofilter requires javac diagnostics (file:line:error).\n"
                + "Raw output:\n"
                + err);
      }

      if (errors.isEmpty()) {
        throw new RuntimeException(
            "External compile failed but no javac-style errors found.\n" + err);
      }

      int touched = 0;

      for (JError je : errors) {
        Path file = normalizeCompilerPath(je.file);

        System.out.println("[DP] error file exists? " + Files.exists(file) + " :: " + file);

        System.out.println("[DP] error file exists? " + Files.exists(file) + " :: " + file);

        int removed = JavaRunner.removeInvariantRegion(file, je.line);
        if (removed > 0) {
          touched += removed;
          System.out.println("[DP] disabled invariant in " + file + " @ line " + je.line);
        }
      }

      if (touched == 0) {
        throw new RuntimeException(
            "External autofilter stuck: no invariant regions removed.\n" + err);
      }
    }

    throw new RuntimeException("External autofilter failed after " + maxPasses + " passes");
  }

  public static void compile(Path workProjectRoot, Path compileScript) throws Exception {

    if (!Files.isExecutable(compileScript)) {
      throw new IllegalArgumentException("[DP] Compile script not executable: " + compileScript);
    }

    ProcessBuilder pb = new ProcessBuilder(compileScript.toAbsolutePath().toString());

    pb.directory(workProjectRoot.toFile());
    pb.inheritIO(); // IMPORTANT: do NOT swallow output

    int exit = pb.start().waitFor();

    if (exit != 0) {
      throw new RuntimeException("External compile failed (exit=" + exit + "): " + compileScript);
    }
  }

  private static Path normalizeCompilerPath(String file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Compiler error reported null/empty file path");
    }

    // macOS fix: javac reports /private/var but files live in /var
    if (file.startsWith("/private/var/")) {
      file = file.substring("/private".length());
    }

    return Path.of(file).normalize();
  }
}
