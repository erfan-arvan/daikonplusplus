package edu.njit.jerse.daikonplusplus;

import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil;
import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil.JError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Utility class for compiling instrumented projects using an external build script
 * with automatic invariant filtering.
 *
 * <p>The provided script acts as the compiler (e.g., Gradle or Maven build).
 * If compilation fails, compiler errors are parsed and used to:
 * <ul>
 *   <li>Disable invariant regions causing errors</li>
 *   <li>Restore original files if local removal is insufficient</li>
 * </ul>
 *
 * <p>The process repeats until compilation succeeds or no further progress is possible.
 */
public final class ExternalCompileRunner {

  private ExternalCompileRunner() {}

  /**
   * Runs an external compile script with automatic invariant filtering.
   *
   * <p>If the script fails, compiler output is parsed to locate errors, and the corresponding
   * invariant regions are disabled or source files are restored. The process repeats until
   * the script succeeds or no progress can be made.
   *
   * @param workProjectRoot root of the working project
   * @param workSrcRoot root of the instrumented source tree
   * @param originalSrcRoot root of the original source tree
   * @param compileScript executable script used for compilation
   * @param maxModifyPasses number of passes that attempt invariant-level removal before fallback
   * @throws Exception if compilation ultimately fails
   */
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
          Path raw = normalizeCompilerPath(je.file);

          // case 1: relative path → resolve against project root
          if (!raw.isAbsolute()) {
            raw = workProjectRoot.resolve(raw);
          }

          // case 2: broken "/project-..." path → fix it
          if (!Files.exists(raw)) {
            String s = raw.toString();
            int idx = s.indexOf("project-");
            if (idx != -1) {
              String sub = s.substring(idx);
              Path parent = workProjectRoot.getParent();
              if (parent == null) {
                System.out.println("[DP] cannot repair path (no parent): " + workProjectRoot);
                continue;
              }
              raw = parent.resolve(sub);
            }
          }

          file = raw.toRealPath();

          // compute once outside loop if you want (optional optimization)
          Path workSrcRootReal = workSrcRoot.toRealPath();

          if (!file.startsWith(workSrcRootReal)) {
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

  /**
   * Runs an external compile script without invariant filtering.
   *
   * @param workProjectRoot root of the working project
   * @param compileScript executable script used for compilation
   * @throws Exception if the script fails
   */
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

  /**
   * Normalizes a file path reported by the compiler.
   *
   * <p>Handles platform-specific inconsistencies (e.g., macOS "/private/var" prefix)
   * and returns a normalized {@link Path}.
   *
   * @param file file path reported by the compiler
   * @return normalized path
   */
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

  /**
   * Restores a source file in the working project from the original source tree.
   *
   * @param brokenFile file that failed compilation
   * @param workSrcRoot root of the working source tree
   * @param originalSrcRoot root of the original source tree
   * @return 1 if the file was restored, 0 otherwise
   */
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
