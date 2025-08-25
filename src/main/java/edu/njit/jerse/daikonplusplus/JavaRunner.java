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
}
