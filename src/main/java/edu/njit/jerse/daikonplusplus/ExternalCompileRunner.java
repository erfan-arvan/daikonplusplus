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
      Path workSrcRoot,
      Path originalSrcRoot,
      Path compileScript,
      int maxModifyPasses)
      throws Exception {

    if (!Files.isExecutable(compileScript)) {
      throw new IllegalArgumentException("[DP] Compile script not executable: " + compileScript);
    }

    Path errLog = workProjectRoot.resolve("dp-external-compile.err");

    int pass = 1;
    int maxTotalPasses = maxModifyPasses + 20;

    while (true) {

      System.out.println(
          "[DP] === external autofilter pass "
              + pass
              + " (script="
              + compileScript.getFileName()
              + ") ===");

      boolean inModifyPhase = pass <= maxModifyPasses;
      System.out.println("[DP] inModifyPhase = " + inModifyPhase);

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

      System.out.println("\n[DP-DEBUG] ===== RAW COMPILER OUTPUT =====");
      System.out.println(err.length() > 1500 ? err.substring(0, 1500) + "\n...[truncated]" : err);
      System.out.println("[DP-DEBUG] =================================\n");

      Path seen = workProjectRoot.resolve("dp-autofilter-seen.txt");
      Files.writeString(seen, err, StandardCharsets.UTF_8);
      System.out.println("[DP] wrote autofilter input to " + seen);

      List<JError> errors = InvariantAutoFilterUtil.parseExternalCompilerErrors(err);

      System.out.println("[DP-DEBUG] Parsed errors count = " + errors.size());

      for (int i = 0; i < Math.min(errors.size(), 10); i++) {
        JError e = errors.get(i);
        System.out.println("[DP-DEBUG] ERROR[" + i + "] → " + e.file + ":" + e.line);
      }

      if (errors.isEmpty()) {
        throw new RuntimeException(
            "External build failed but no javac-style errors were found.\n"
                + "Autofilter requires javac diagnostics (file:line:error).\n"
                + "Raw output:\n"
                + err);
      }

      int touched = 0;
      Set<String> seenErrors = new HashSet<>();

      for (JError je : errors) {

        String key = je.file + ":" + je.line;
        if (!seenErrors.add(key)) continue;

        Path file;
        try {
          file = normalizeCompilerPath(je.file).toRealPath();

          if (!file.startsWith(workSrcRoot.toRealPath())) {
            System.out.println("[DP] skipping path outside workSrcRoot: " + file);
            continue;
          }

        } catch (Exception e) {
          System.out.println("[DP] skipping invalid path: " + je.file);
          continue;
        }

        System.out.println("[DP] error file exists? " + Files.exists(file) + " :: " + file);

        if (inModifyPhase) {
          int removed = JavaRunner.removeInvariantRegion(file, je.line);
          System.out.println("[DP]   removeInvariantRegion → " + removed);

          if (removed > 0) {
            touched += removed;
            System.out.println("[DP] disabled invariant in " + file + " @ line " + je.line);
            continue;
          }
        }

        int restored = restoreOriginalFile(file, workSrcRoot, originalSrcRoot);
        System.out.println("[DP]   restoreOriginalFile → " + restored);

        if (restored > 0) {
          touched += restored;
          System.out.println("[DP] restored original file: " + file);
        }
      }

      System.out.println("[DP] touched = " + touched);

      if (touched == 0) {
        throw new RuntimeException(
            "External compile failed with no progress (no invariants removed or files restored).\n"
                + err);
      }

      if (pass >= maxTotalPasses) {
        throw new RuntimeException("External autofilter failed after " + pass + " passes\n" + err);
      }

      pass++;
    }
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

  private static int restoreOriginalFile(Path brokenFile, Path workSrcRoot, Path originalSrcRoot) {

    try {
      // Canonical paths (fixes /scratch vs /mmfs1)
      Path workRootReal = workSrcRoot.toRealPath();
      Path brokenReal = brokenFile.toRealPath();

      // Only operate inside work tree
      if (!brokenReal.startsWith(workRootReal)) {
        System.out.println("[DP] skip restore (outside work root): " + brokenFile);
        return 0;
      }

      // RELATIVE mapping (core fix)
      Path rel = workRootReal.relativize(brokenReal);

      Path original = originalSrcRoot.resolve(rel).normalize();

      if (!Files.isRegularFile(original)) {
        System.out.println("[DP] original not found: " + original);
        return 0;
      }

      original = original.toRealPath();

      Path parent = brokenFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }

      Files.copy(
          original,
          brokenFile,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);

      System.out.println("[DP] restored: " + brokenFile);
      return 1;

    } catch (Exception e) {
      System.out.println("[DP] restore failed: " + e);
      return 0;
    }
  }
}
