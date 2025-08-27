package edu.njit.jerse.daikonplusplus;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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
   * Compiles all {@code .java} files under {@code srcRoot} to {@code classesDir} with {@code -cp
   * classpath}.
   */
  public static void compile(Path srcRoot, Path classesDir, String classpath) throws Exception {
    Files.createDirectories(classesDir);

    // gather source files and write an @argfile to avoid arg-length limits
    List<Path> sources = new ArrayList<>();
    try (var walk = Files.walk(srcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
    }

    if (sources.isEmpty()) {
      throw new IllegalStateException("No .java files found under " + srcRoot);
    }

    Path argFile = classesDir.resolve("dp_sources.txt");
    try (PrintWriter pw =
        new PrintWriter(Files.newBufferedWriter(argFile, StandardCharsets.UTF_8))) {
      for (Path s : sources) pw.println(s.toAbsolutePath().normalize());
    }

    String javacExe = toolPath("javac");
    List<String> cmd = new ArrayList<>();
    cmd.add(javacExe);
    cmd.add("-encoding");
    cmd.add("UTF-8");
    cmd.add("-g");
    cmd.add("-cp");
    cmd.add(classpath);
    cmd.add("-d");
    cmd.add(classesDir.toString());
    cmd.add("@" + argFile.toString());

    Path outLog = classesDir.resolve("dp-javac.out");
    Path errLog = classesDir.resolve("dp-javac.err");
    System.out.println(">>> Compiling with javac: " + String.join(" ", cmd));
    runProcess(cmd, srcRoot, outLog, errLog, true);
  }

  /** Runs {@code java -cp classpath mainClass [args...]} and captures stdout to {@code runLog}. */
  public static void run(String mainClass, String classpath, List<String> args, Path runLog)
      throws Exception {
    // ensure the log directory exists; handle the case where runLog has no
    // parent
    Path parent = runLog.getParent();
    Path logDir = (parent != null) ? parent : Path.of(".");
    Files.createDirectories(logDir);

    String javaExe = toolPath("java");

    List<String> cmd = new ArrayList<>();
    cmd.add(javaExe);
    cmd.add("-cp");
    cmd.add(classpath);
    cmd.add(mainClass);
    cmd.addAll(args);

    Path errLog = logDir.resolve("daikonpp-run.err");
    System.out.println(">>> Running with java: " + String.join(" ", cmd));
    runProcess(cmd, Path.of("."), runLog, errLog, false);
  }

  // ---- internals ----

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

  private static void runProcess(
      List<String> cmd, Path workDir, Path outLog, Path errLog, boolean failOnNonZero)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(workDir.toFile());
    pb.redirectOutput(outLog.toFile());
    pb.redirectError(errLog.toFile());
    Process p = pb.start();
    int code = p.waitFor();
    if (failOnNonZero && code != 0) {
      String err = Files.exists(errLog) ? Files.readString(errLog) : "";
      throw new RuntimeException(
          cmd.get(0) + " exited with " + code + (err.isBlank() ? "" : ("\n" + err)));
    }
  }

  // --- NEW: compile with iterative filtering of failing 1-line invariants ---
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

  // --- NEW: comment a single source line if it looks like an injected one-liner ---
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
              || // your one-liner pattern
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

      // Replace the whole line with one comment, keeping original inline after the reason.
      // (Requested style: inline comment that carries the original invariant text)
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

  // --- NEW: non-throwing process runner so we can loop ---
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
