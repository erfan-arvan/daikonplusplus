package edu.njit.jerse.daikonplusplus;

import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil;
import edu.njit.jerse.daikonplusplus.util.InvariantAutoFilterUtil.JError;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** FINAL WORKING JAVARUNNER. Marker-based filtering only. Supports oneline + block invariants. */
public final class JavaRunner {

  // Old style (oneline guards)
  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";

  // New style (block guards)
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  private static final long EXTERNAL_RUN_TIMEOUT_MINUTES = 1440;

  private JavaRunner() {}

  // ============================================================
  // RUN JAVA
  // ============================================================

  public static void run(String mainClass, String classpath, List<String> args, Path logFile)
      throws Exception {

    Files.createDirectories(Optional.ofNullable(logFile.getParent()).orElse(Path.of(".")));

    List<String> cmd = new ArrayList<>();
    cmd.add(tool("java"));
    cmd.add("-Dfile.encoding=UTF-8");
    cmd.add("-Xshare:off");

    Path logDir = Optional.ofNullable(logFile.getParent()).orElse(Path.of("."));
    cmd.add("-DDP_INV_DIR=" + logDir.resolve(".daikonpp-events").toAbsolutePath());

    cmd.add("-cp");
    cmd.add(classpath);
    cmd.add(mainClass);
    cmd.addAll(args);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);

    Process p = pb.start();

    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MINUTES.toNanos(EXTERNAL_RUN_TIMEOUT_MINUTES);

    try (BufferedReader r =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w =
            Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

      String line;
      while (true) {
        while (r.ready() && (line = r.readLine()) != null) {
          w.write(line);
          w.newLine();
          w.flush();
        }

        if (!p.isAlive()) break;

        if (System.nanoTime() > deadline) {
          w.write("[DP] Runner TIMED OUT");
          w.newLine();
          w.flush();
          p.destroyForcibly();
          break;
        }
        Thread.sleep(100);
      }
    }

    int code = p.waitFor();
    if (code != 0) {
      Files.writeString(
          logFile, "\n[DP] Child JVM exit code: " + code + "\n", StandardOpenOption.APPEND);
    }

    appendDpEvents(logDir.resolve(".daikonpp-events"), logFile);
  }

  // ============================================================
  // COMPILE WITH AUTOFILTER (JAVAC MODE)
  // ============================================================

  public static void compileWithAutoFilter(
      Path workSrcRoot,
      Path originalSrcRoot,
      Path classesDir,
      String classpath,
      int maxModifyPasses)
      throws Exception {

    Files.createDirectories(classesDir);

    System.out.println("[DP] compileWithAutoFilter START");
    System.out.println("[DP] workSrcRoot = " + workSrcRoot.toAbsolutePath());
    System.out.println("[DP] originalSrcRoot = " + originalSrcRoot.toAbsolutePath());

    List<Path> sources = new ArrayList<>();
    try (var walk = Files.walk(workSrcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
    }

    System.out.println("[DP] Found Java sources: " + sources.size());

    if (sources.isEmpty()) {
      throw new RuntimeException("No Java sources under " + workSrcRoot);
    }

    Path argFile = classesDir.resolve("dp_sources.txt");
    try (PrintWriter pw =
        new PrintWriter(Files.newBufferedWriter(argFile, StandardCharsets.UTF_8))) {
      for (Path s : sources) {
        pw.println(s.toAbsolutePath());
      }
    }

    List<String> base = new ArrayList<>();
    base.add(tool("javac"));
    base.add("-encoding");
    base.add("UTF-8");
    base.add("-g");
    base.add("-proc:none");
    base.add("-cp");
    base.add(classpath);
    base.add("-d");
    base.add(classesDir.toString());
    base.add("@" + argFile);

    Path outLog = classesDir.resolve("dp-javac.out");
    Path errLog = classesDir.resolve("dp-javac.err");

    int pass = 1;
    int maxTotalPasses = maxModifyPasses + 20;

    while (true) {

      System.out.println("\n[DP] ===== PASS " + pass + " =====");

      boolean inModifyPhase = pass <= maxModifyPasses;
      System.out.println("[DP] inModifyPhase = " + inModifyPhase);

      int code = runProcess(base, workSrcRoot, outLog, errLog);
      System.out.println("[DP] javac exit code = " + code);

      if (code == 0) {
        System.out.println("[DP] Compilation SUCCESS");
        return;
      }

      String err = Files.exists(errLog) ? Files.readString(errLog) : "";

      System.out.println("[DP] --- RAW STDERR (first 1000 chars) ---");
      System.out.println(err.length() > 1000 ? err.substring(0, 1000) + "\n...[truncated]" : err);
      System.out.println("[DP] -------------------------------------");

      List<JError> errors = InvariantAutoFilterUtil.parseErrors(err);

      System.out.println("[DP] Parsed errors count = " + errors.size());
      for (JError e : errors) {
        System.out.println("[DP] ERROR → " + e.file + ":" + e.line);
      }

      int touched = 0;

      Set<String> seen = new HashSet<>();

      for (JError je : errors) {

        String key = je.file + ":" + je.line;
        if (!seen.add(key)) continue;

        Path file = Path.of(je.file).toAbsolutePath().normalize();

        System.out.println("[DP] Processing → " + file + ":" + je.line);

        if (inModifyPhase) {
          int removed = removeInvariantRegion(file, je.line);
          System.out.println("[DP]   removeInvariantRegion → " + removed);

          if (removed > 0) {
            touched += removed;
            continue;
          }
        }

        int restored = restoreOriginalFile(file, workSrcRoot, originalSrcRoot);
        System.out.println("[DP]   restoreOriginalFile → " + restored);

        touched += restored;
      }

      System.out.println("[DP] touched = " + touched);

      if (touched == 0) {
        System.out.println("[DP] ❌ NO PROGRESS THIS PASS");
        throw new RuntimeException("javac failed with no progress:\n" + firstErrorMsg(errors));
      }

      if (pass >= maxTotalPasses) {
        throw new RuntimeException("javac still failing after " + pass + " passes");
      }

      pass++;
    }
  }

  private static String firstErrorMsg(List<JError> list) {
    return list.isEmpty()
        ? "<none>"
        : list.get(0).file + ":" + list.get(0).line + " — " + list.get(0).msg;
  }

  // ============================================================
  // INVARIANT REMOVAL
  // ============================================================

  static int removeInvariantRegion(Path file, int lineno) {
    try {
      if (!Files.isRegularFile(file)) return 0;

      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      int begin = -1, end = -1;

      int idx = Math.max(0, Math.min(lineno - 1, lines.size() - 1));

      for (int i = idx; i >= 0; i--) {
        if (lines.get(i).contains(ONELINE_BEGIN) || lines.get(i).contains(BLOCK_BEGIN)) {
          begin = i;
          break;
        }
      }
      for (int i = idx; i < lines.size(); i++) {
        if (lines.get(i).contains(ONELINE_END) || lines.get(i).contains(BLOCK_END)) {
          end = i;
          break;
        }
      }

      if (begin < 0 || end < 0) return 0;

      for (int i = begin; i <= end; i++) {
        if (!lines.get(i).trim().startsWith("// [DP] disabled")) {
          lines.set(i, "// [DP] disabled invariant :: " + lines.get(i));
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
      return 1;

    } catch (Exception e) {
      return 0;
    }
  }

  // ============================================================
  // RESTORE ORIGINAL FILE
  // ============================================================

  private static int restoreOriginalFile(Path brokenFile, Path workSrcRoot, Path originalSrcRoot) {

    try {
      Path workRoot = workSrcRoot.toAbsolutePath().normalize();
      Path broken = brokenFile.toAbsolutePath().normalize();

      if (!broken.startsWith(workRoot)) return 0;

      Path rel = workRoot.relativize(broken);
      Path original = originalSrcRoot.resolve(rel);

      if (!Files.isRegularFile(original)) return 0;

      Path parent = broken.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(
          original,
          broken,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);
      return 1;

    } catch (Exception e) {
      return 0;
    }
  }

  // ============================================================
  // UTILITIES
  // ============================================================

  private static int runProcess(List<String> cmd, Path wd, Path out, Path err) throws Exception {

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(wd.toFile());
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    return pb.start().waitFor();
  }

  private static String tool(String base) {
    String ext = isWin() ? ".exe" : "";
    String home = System.getenv("JAVA_HOME");
    if (home != null) {
      Path p = Path.of(home, "bin", base + ext);
      if (Files.isRegularFile(p)) return p.toString();
    }
    return base + ext;
  }

  private static boolean isWin() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  public static String joinCp(String... parts) {
    String sep = System.getProperty("path.separator");
    return String.join(sep, Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
  }

  // ============================================================
  // EVENT MERGE
  // ============================================================

  private static void appendDpEvents(Path invDir, Path runLog) {
    try {
      if (!Files.isDirectory(invDir)) return;

      List<Path> files = new ArrayList<>();
      try (var s = Files.list(invDir)) {
        s.filter(
                p -> {
                  Path name = p.getFileName();
                  return name != null && name.toString().startsWith("dp-events-");
                })
            .sorted()
            .forEach(files::add);
      }

      for (Path f : files) {
        Files.writeString(
            runLog,
            Files.readString(f, StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    } catch (Exception ignored) {
    }
  }

  public static void runExternalScript(Path script, Path workDir, String fullRunCp, Path runLog)
      throws IOException, InterruptedException {

    if (!Files.isRegularFile(script)) {
      throw new IllegalArgumentException("[DP] External runner script not found: " + script);
    }

    if (!Files.isExecutable(script)) {
      throw new IllegalArgumentException(
          "[DP] External runner script is not executable: "
              + script
              + " (did you forget chmod +x?)");
    }

    Files.createDirectories(Optional.ofNullable(runLog.getParent()).orElse(Path.of(".")));

    ProcessBuilder pb = new ProcessBuilder(script.toAbsolutePath().toString());

    // Run inside the working project copy
    pb.directory(workDir.toFile());

    Map<String, String> env = pb.environment();

    // Optional classpath exposure for scripts that want it
    if (fullRunCp != null && !fullRunCp.isBlank()) {
      env.put("DP_DAIKONPP_CLASSPATH", fullRunCp);
    }

    env.put("DP_RUN_LOG", runLog.toAbsolutePath().toString());

    Path invDir = workDir.resolve(".daikonpp-events");
    Files.createDirectories(invDir);

    // Make invariants visible to child JVMs
    env.put("DP_INV_DIR", invDir.toAbsolutePath().toString());

    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath();

    env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + jvmArgs).trim());
    env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + jvmArgs).trim());
    env.put("GRADLE_OPTS", (env.getOrDefault("GRADLE_OPTS", "") + " " + jvmArgs).trim());

    pb.redirectErrorStream(true);
    Process p = pb.start();

    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MINUTES.toNanos(EXTERNAL_RUN_TIMEOUT_MINUTES);

    try (BufferedReader r =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter w =
            Files.newBufferedWriter(
                runLog,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

      String line;

      while (true) {
        while (r.ready() && (line = r.readLine()) != null) {
          w.write(line);
          w.newLine();
          w.flush(); // keep live output
        }

        if (!p.isAlive()) break;

        if (System.nanoTime() > deadline) {
          w.write(
              "[DP] External runner TIMED OUT after " + EXTERNAL_RUN_TIMEOUT_MINUTES + " minutes");
          w.newLine();
          w.flush();
          p.destroyForcibly();
          break;
        }

        Thread.sleep(100);
      }
    }

    int exit = p.isAlive() ? -1 : p.exitValue();

    appendDpEvents(invDir, runLog);

    if (exit != 0) {
      Files.writeString(
          runLog,
          "\n[DP] External runner exited with code " + exit + "\n",
          StandardOpenOption.APPEND);
    }
  }
}
