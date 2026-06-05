package edu.njit.jerse.daikonplusplus;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thin wrapper around {@code javac} and {@code java}.
 *
 * <p>Uses {@code JAVA_HOME/bin} if available; otherwise expects the tools on PATH.
 */
public final class JavaRunner {
  private JavaRunner() {}

  /** joins classpath segments using the platform-specific separator. */
  public static String joinCp(String... parts) {
    String sep = System.getProperty("path.separator");
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p == null || p.isBlank()) continue;
      if (sb.length() > 0) sb.append(sep);
      sb.append(p);
    }
    return sb.toString();
  }

  /**
   * Runs {@code java -cp classpath mainClass [args...]} and captures stdout to {@code runLog}.
   *
   * <p>Delegates to {@link #run(String, String, List, Path, long, Path)} with no effective timeout
   * and no disabled-invariants file.
   */
  public static void run(String mainClass, String classpath, List<String> args, Path logFile)
      throws Exception {
    run(mainClass, classpath, args, logFile, Long.MAX_VALUE / 2, null);
  }

  /**
   * Runs {@code java -cp classpath mainClass [args...]} and captures stdout to {@code runLog}.
   *
   * <p>Delegates to {@link #run(String, String, List, Path, long, Path)} with no
   * disabled-invariants file.
   *
   * @param timeoutSeconds wall-clock seconds to wait before killing the process
   * @return {@code true} if the run timed out, {@code false} on normal completion
   */
  public static boolean run(
      String mainClass, String classpath, List<String> args, Path logFile, long timeoutSeconds)
      throws Exception {
    return run(mainClass, classpath, args, logFile, timeoutSeconds, null);
  }

  /**
   * Runs {@code java -cp classpath mainClass [args...]} and captures stdout to {@code runLog}.
   *
   * <p>If {@code disabledFile} exists, its path is passed to the child JVM as {@code
   * -DDP_DISABLED_FILE=...} so that {@code daikonpp.DpRuntime} skips all invariants whose UUIDs
   * appear in that file.
   *
   * <p>If the child JVM does not finish within {@code timeoutSeconds}, it is killed forcibly and
   * {@code true} is returned so the caller can apply timeout-recovery logic.
   *
   * @param timeoutSeconds wall-clock seconds to wait before killing the process
   * @param disabledFile path to a newline-delimited file of disabled invariant UUIDs, or {@code
   *     null} to skip
   * @return {@code true} if the run timed out, {@code false} on normal completion
   */
  public static boolean run(
      String mainClass,
      String classpath,
      List<String> args,
      Path logFile,
      long timeoutSeconds,
      @Nullable Path disabledFile)
      throws Exception {
    // Ensure parent dir exists even if logFile has no parent
    Path parent = logFile.getParent();
    Files.createDirectories(parent == null ? Path.of(".") : parent);

    List<String> cmd = new ArrayList<>();
    cmd.add(toolPath("java"));
    cmd.add("-Dfile.encoding=UTF-8");
    cmd.add("-Xshare:off");
    if (disabledFile != null && Files.exists(disabledFile)) {
      cmd.add("-DDP_DISABLED_FILE=" + disabledFile.toAbsolutePath());
    }
    cmd.add("-cp");
    cmd.add(classpath);
    cmd.add(mainClass);
    cmd.addAll(args);

    System.out.println(">>> Running with java: " + String.join(" ", cmd));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true); // merge stderr into stdout
    Process p = pb.start();

    // Pump stdout+stderr to the log file in a background thread so we can use waitFor(timeout).
    Thread pumper =
        new Thread(
            () -> {
              try (var in = p.getInputStream();
                  var out =
                      Files.newOutputStream(
                          logFile,
                          StandardOpenOption.CREATE,
                          StandardOpenOption.TRUNCATE_EXISTING)) {
                pump(in, out);
              } catch (java.io.IOException ignored) {
              }
            },
            "dp-log-pumper");
    pumper.setDaemon(true);
    pumper.start();

    boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    if (!finished) {
      p.destroyForcibly();
      pumper.join(2_000); // brief flush window before appending to the log
      Files.writeString(
          logFile,
          System.lineSeparator()
              + "[DP] Run timed out after "
              + timeoutSeconds
              + "s"
              + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      return true;
    }

    pumper.join(); // wait for all output to be written before reading size below
    int code = p.exitValue();

    // If it failed, stamp the exit code, so we can see it in the run log
    if (code != 0) {
      Files.writeString(
          logFile,
          System.lineSeparator() + "[DP] Child JVM exit code: " + code + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }

    // Final sanity: make it obvious if nothing was produced
    if (Files.size(logFile) == 0L) {
      Files.writeString(
          logFile,
          "[DP] WARNING: child JVM produced no output (stdout/stderr). "
              + "Check mainClass/classpath or early crashes.",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
    return false;
  }

  /**
   * Appends {@code stuckId} to {@code disabledFile} (one UUID per line). On the next run, the child
   * JVM receives {@code -DDP_DISABLED_FILE=<path>} and {@code daikonpp.DpRuntime.DISABLED} skips
   * every invariant whose UUID is in that file — across all return paths simultaneously, without
   * touching source code.
   *
   * <p>The file is created if it does not exist; existing entries are preserved.
   */
  public static void disableInvariant(Path disabledFile, UUID stuckId) throws IOException {
    Path dirPath = disabledFile.getParent();
    Files.createDirectories(dirPath != null ? dirPath : Path.of("."));
    Files.writeString(
        disabledFile,
        stuckId.toString() + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    System.out.println("    ↳ Disabled stuck invariant " + stuckId + " → " + disabledFile);
  }

  // ---- internals ----

  private static void pump(java.io.InputStream in, java.io.OutputStream out)
      throws java.io.IOException {
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    out.flush();
  }

  private static String toolPath(String base) {
    String ext = isWindows() ? ".exe" : "";
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null && !javaHome.isBlank()) {
      Path p = Path.of(javaHome, "bin", base + ext);
      if (Files.isRegularFile(p)) return p.toString();
    }
    return base + ext; // rely on PATH
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  /**
   * Compiles all Java files under {@code srcRoot}, automatically commenting out one-line invariants
   * that cause compilation errors. Repeats until successful or {@code maxPasses} is reached.
   *
   * @param srcRoot source root containing .java files
   * @param classesDir output directory for compiled classes
   * @param classpath classpath for javac
   * @param maxPasses maximum number of compile attempts
   * @throws Exception if I/O fails or compilation still fails after max passes
   */
  public static void compileWithAutoFilter(
      Path srcRoot, Path classesDir, String classpath, int maxPasses) throws Exception {

    Files.createDirectories(classesDir);

    // gather source files and write @argfile
    List<Path> sources = new ArrayList<>();
    try (var walk = Files.walk(srcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
    }
    if (sources.isEmpty()) throw new IllegalStateException("No .java files found under " + srcRoot);

    Path argFile = classesDir.resolve("dp_sources.txt");
    try (PrintWriter pw =
        new PrintWriter(Files.newBufferedWriter(argFile, StandardCharsets.UTF_8))) {
      for (Path s : sources) pw.println(s.toAbsolutePath().normalize());
    }

    // shared javac cmd
    String javacExe = toolPath("javac");
    List<String> baseCmd = new ArrayList<>();
    baseCmd.add(javacExe);
    baseCmd.add("-encoding");
    baseCmd.add("UTF-8");
    baseCmd.add("-g");
    baseCmd.add("-cp");
    baseCmd.add(classpath);
    baseCmd.add("-d");
    baseCmd.add(classesDir.toString());
    baseCmd.add("@" + argFile.toString());

    // logs (overwrite per pass)
    Path outLog = classesDir.resolve("dp-javac.out");
    Path errLog = classesDir.resolve("dp-javac.err");

    // iterate: compile -> parse errors -> comment failing inv lines -> repeat
    int pass = 1;
    while (true) {
      System.out.println(
          ">>> Compiling with javac (pass " + pass + "): " + String.join(" ", baseCmd));
      int code = runProcessNoThrow(baseCmd, srcRoot, outLog, errLog);

      if (code == 0) {
        System.out.println(">>> javac OK on pass " + pass);
        return;
      }

      // parse errors and decide what to comment
      String err = Files.exists(errLog) ? Files.readString(errLog) : "";
      List<JavacError> errs =
          parsePrimaryJavacErrors(err); // only lines like ".../Foo.java:123: error: ..."

      // map -> comment
      int commented = 0;
      for (JavacError je : errs) {
        Path file = Path.of(je.file).toAbsolutePath().normalize();
        if (!file.startsWith(srcRoot)) continue; // ignore external/dep paths
        commented += commentInvariantLine(file, je.line, je.message);
      }

      if (commented == 0) {
        throw new RuntimeException(
            "javac failed, but no one-line invariants were found to comment.\n" + err);
      }

      if (pass++ >= Math.max(1, maxPasses)) {
        throw new RuntimeException("javac still failing after " + (pass - 1) + " passes.\n" + err);
      }
    }
  }

  // parse primary javac error lines: "<path>.java:<line>: error: <message>" ---
  private static List<JavacError> parsePrimaryJavacErrors(String stderr) {
    List<JavacError> out = new ArrayList<>();
    if (stderr == null || stderr.isBlank()) return out;

    // Matches: /path/Foo.java:123: error: message...
    // Works across platforms and with leading whitespace.
    final java.util.regex.Pattern pat =
        java.util.regex.Pattern.compile(
            "^\\s*(.*\\.java):(\\d+):\\s+error:\\s+(.*)\\s*$", java.util.regex.Pattern.MULTILINE);

    final java.util.regex.Matcher m = pat.matcher(stderr);
    while (m.find()) {
      final String fileStr = m.group(1);
      final String lineStr = m.group(2);
      final String msgStr = m.group(3);

      // If any are missing, skip this match (keeps checker happy).
      if (fileStr == null || lineStr == null || msgStr == null) continue;

      final int line;
      try {
        line = Integer.parseInt(lineStr);
      } catch (NumberFormatException nfe) {
        continue; // malformed line number; ignore this match
      }

      // All non-null now; safe to construct.
      out.add(new JavacError(fileStr, line, msgStr));
    }
    return out;
  }

  private static final class JavacError {
    final String file;
    final int line;
    final String message;

    JavacError(String file, int line, String message) {
      this.file = file;
      this.line = line;
      this.message = message;
    }
  }

  // comment a single source line if it looks like an injected one-liner ---
  private static int commentInvariantLine(Path file, int lineNumber, String reason) {
    try {
      if (!Files.isRegularFile(file)) return 0;
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      int idx = lineNumber - 1;
      if (idx < 0 || idx >= lines.size()) return 0;

      String src = lines.get(idx);
      String trimmed = src.strip();

      // Heuristics: treat as our one-line invariant if it contains our signatures
      boolean looksLikeInvariant =
          trimmed.startsWith("try { if (!(")
              || // one-liner pattern
              trimmed.contains("\"type\\\":\\\"INV_FAIL\\\"")
              || // the JSON marker
              trimmed.contains("/*__DP_ONELINE_BEGIN__*/")
              || // if markers survived
              trimmed.contains("__dp_ex_"); // our unique catch var prefix

      // already commented?
      if (trimmed.startsWith("//Failed Invariant in Compilation:")
          || trimmed.startsWith("// try {")
          || trimmed.startsWith("//try{")) {
        return 0;
      }

      if (!looksLikeInvariant) {
        // Skip non-ours to avoid breaking user code
        return 0;
      }

      // Replace the whole line with one comment, keeping original inline after the reason
      String commented = "//Failed Invariant in Compilation: " + reason + " :: " + src.trim();
      lines.set(idx, commented);

      Files.write(file, lines, StandardCharsets.UTF_8);
      System.out.println("    ↳ Commented " + file + ":" + lineNumber);
      return 1;

    } catch (Exception e) {
      System.err.println(
          "    ! Failed to comment " + file + ":" + lineNumber + " — " + e.getMessage());
      return 0;
    }
  }

  // non-throwing process runner so we can loop ---
  private static int runProcessNoThrow(List<String> cmd, Path workDir, Path outLog, Path errLog)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(workDir.toFile());
    pb.redirectOutput(outLog.toFile());
    pb.redirectError(errLog.toFile());
    Process p = pb.start();
    return p.waitFor();
  }
}
