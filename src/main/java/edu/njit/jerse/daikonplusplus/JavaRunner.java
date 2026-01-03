package edu.njit.jerse.daikonplusplus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** FINAL WORKING JAVARUNNER. Marker-based filtering only. Supports oneline + block invariants. */
public final class JavaRunner {

  // Old style (oneline guards)
  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";

  // New style (block guards with comments)
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  private static final long EXTERNAL_RUN_TIMEOUT_MINUTES = 20;

  private JavaRunner() {}

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
          w.write("[DP] Runner TIMED OUT after " + EXTERNAL_RUN_TIMEOUT_MINUTES + " minutes");
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
          logFile,
          "\n[DP] Child JVM exit code: " + code + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }

    Path invDir = logDir.resolve(".daikonpp-events");
    appendDpEvents(invDir, logFile);
  }

  /**
   * Compile the working copy, automatically commenting out invariant regions that fail to compile
   * and, when no invariant markers are present for a given error, restoring that file from the
   * original source tree.
   *
   * @param workSrcRoot the working-copy root (where invariants are injected)
   * @param originalSrcRoot the original user source root (clean copy, no injection)
   */
  public static void compileWithAutoFilter(
      Path workSrcRoot,
      Path originalSrcRoot,
      Path classesDir,
      String classpath,
      int maxModifyPasses)
      throws Exception {

    Files.createDirectories(classesDir);

    List<Path> sources = new ArrayList<>();
    try (var walk = Files.walk(workSrcRoot)) {
      walk.filter(f -> f.toString().endsWith(".java")).forEach(sources::add);
    }

    if (sources.isEmpty()) {
      throw new RuntimeException("No Java sources under " + workSrcRoot);
    }

    Path argFile = classesDir.resolve("dp_sources.txt");
    try (var pw = new PrintWriter(Files.newBufferedWriter(argFile, StandardCharsets.UTF_8))) {
      for (Path s : sources) {
        pw.println(s.toAbsolutePath());
      }
    }

    // Build mutable javac command
    List<String> base = new ArrayList<>();
    base.add(tool("javac"));
    base.add("-encoding");
    base.add("UTF-8");
    base.add("-g");
    // disable annotation processing here, regardless of wrapper
    base.add("-proc:none");

    boolean debugJavac = "1".equals(System.getenv("DP_DEBUG_JAVAC"));
    if (debugJavac) {
      base.add("-XprintProcessorInfo");
      base.add("-XprintRounds");
    }

    base.add("-cp");
    base.add(classpath);
    base.add("-d");
    base.add(classesDir.toString());
    base.add("@" + argFile.toString());

    Path outLog = classesDir.resolve("dp-javac.out");
    Path errLog = classesDir.resolve("dp-javac.err");

    int pass = 1;
    // optional hard safety cap so we never loop forever
    int maxTotalPasses = maxModifyPasses + 20;

    while (true) {

      System.out.println("[DP] === javac pass " + pass + " ===");
      boolean inModifyPhase = pass <= maxModifyPasses;
      System.out.println("[DP]   phase = " + (inModifyPhase ? "INVARIANT-MODIFY" : "RESTORE-ONLY"));

      int code = runProcess(base, workSrcRoot, outLog, errLog);
      System.out.println("[DP] javac exit code on pass " + pass + ": " + code);

      if (code == 0) {
        System.out.println("[DP] javac succeeded after " + pass + " passes");
        return;
      }

      String err = Files.exists(errLog) ? Files.readString(errLog) : "";
      List<JError> errors = parse(err);

      System.out.println("[DP] Parsed " + errors.size() + " errors on pass " + pass);
      for (JError je : errors) {
        System.out.println("[DP]   " + je.file + ":" + je.line + " :: " + je.msg);
      }

      int touched = 0;

      for (JError je : errors) {
        Path file = Path.of(je.file);

        // PHASE 1: up to maxModifyPasses, we are allowed to modify invariants
        if (inModifyPhase) {
          int removed = removeInvariantRegion(file, je.line);
          if (removed > 0) {
            touched += removed;
            System.out.println(
                "[DP]   disabled invariant region in " + file + " around line " + je.line);
            continue;
          }
        }

        // In both phases, we may restore structurally broken files
        int restored = restoreOriginalFile(file, workSrcRoot, originalSrcRoot);
        if (restored > 0) {
          touched += restored;
          System.out.println(
              "[DP]   restored original for " + file + " (error at line " + je.line + ")");
        }
      }

      System.out.println(
          "[DP] Pass " + pass + " completed. Files touched (disabled or restored): " + touched);

      // If we didn’t manage to change anything at all, bail out with a clear error
      if (touched == 0) {
        throw new RuntimeException(
            "javac failed and no invariant/restore modifications worked (pass "
                + pass
                + ", phase="
                + (inModifyPhase ? "INVARIANT-MODIFY" : "RESTORE-ONLY")
                + ").\n"
                + firstErrorMsg(errors)
                + "\nRaw javac stderr:\n"
                + err);
      }

      // safety: don’t spin forever if something goes wrong
      if (pass >= maxTotalPasses) {
        String finalErr = Files.exists(errLog) ? Files.readString(errLog) : "";
        throw new RuntimeException(
            "javac still failing after "
                + pass
                + " passes (maxModifyPasses="
                + maxModifyPasses
                + ").\n"
                + finalErr);
      }

      pass++;
    }
  }

  private static String firstErrorMsg(List<JError> list) {
    return list.isEmpty()
        ? "<none>"
        : list.get(0).file + ":" + list.get(0).line + " — " + list.get(0).msg;
  }

  private static List<JError> parse(String stderr) {
    List<JError> out = new ArrayList<>();
    if (stderr == null) return out;

    Matcher m =
        Pattern.compile("^\\s*(.*\\.java):(\\d+):\\s+error:\\s+(.*)$", Pattern.MULTILINE)
            .matcher(stderr);

    while (m.find()) {
      String f = m.group(1);
      String ln = m.group(2);
      String msg = m.group(3);

      if (f == null || ln == null || msg == null) {
        continue;
      }

      int line;
      try {
        line = Integer.parseInt(ln);
      } catch (NumberFormatException ex) {
        continue;
      }

      out.add(new JError(f, line, msg));
    }

    return out;
  }

  private static final class JError {
    final String file;
    final int line;
    final String msg;

    JError(String f, int l, String m) {
      file = f;
      line = l;
      msg = m;
    }
  }

  /**
   * Comments out the entire invariant region (from BEGIN to END, inclusive) that is “closest” to
   * the reported error line. Supports both the old oneline markers and the new multi-line block
   * markers.
   */
  private static int removeInvariantRegion(Path file, int lineno) {
    try {
      if (!Files.isRegularFile(file)) return 0;

      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

      int begin = -1, end = -1;

      // Helper lambdas to check for begin/end markers (either style)
      java.util.function.Predicate<String> isBegin =
          ln -> ln.contains(ONELINE_BEGIN) || ln.contains(BLOCK_BEGIN);
      java.util.function.Predicate<String> isEnd =
          ln -> ln.contains(ONELINE_END) || ln.contains(BLOCK_END);

      int idx = lineno - 1;
      if (idx < 0) idx = 0;
      if (idx >= lines.size()) idx = lines.size() - 1;

      // Primary search: search upward for BEGIN, downward for END
      for (int i = idx; i >= 0; i--) {
        if (isBegin.test(lines.get(i))) {
          begin = i;
          break;
        }
      }
      for (int i = idx; i < lines.size(); i++) {
        if (isEnd.test(lines.get(i))) {
          end = i;
          break;
        }
      }

      // Fallback: global scan for first BEGIN/END pair
      if (begin < 0 || end < 0) {
        begin = -1;
        end = -1;
        for (int i = 0; i < lines.size(); i++) {
          if (isBegin.test(lines.get(i))) {
            begin = i;
            break;
          }
        }
        if (begin >= 0) {
          for (int i = begin; i < lines.size(); i++) {
            if (isEnd.test(lines.get(i))) {
              end = i;
              break;
            }
          }
        }
        if (begin < 0 || end < 0) {
          // No markers at all in this file.
          return 0;
        }
      }

      // Comment out ALL lines between begin and end (inclusive).
      for (int i = begin; i <= end && i < lines.size(); i++) {
        String ln = lines.get(i);
        // Avoid double-commenting if already disabled
        if (!ln.trim().startsWith("// [DP] disabled invariant ::")) {
          lines.set(i, "// [DP] disabled invariant :: " + ln);
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
      return 1;

    } catch (Exception ex) {
      return 0;
    }
  }

  /**
   * Restore a structurally broken file in the working copy from the original source tree.
   *
   * @param brokenFile path reported by javac (inside the working copy)
   * @param workSrcRoot working-copy root
   * @param originalSrcRoot original user source root
   * @return 1 if restored, 0 otherwise
   */
  private static int restoreOriginalFile(Path brokenFile, Path workSrcRoot, Path originalSrcRoot) {
    try {
      Path workRootReal = workSrcRoot.toAbsolutePath().normalize();
      Path brokenReal = brokenFile.toAbsolutePath().normalize();

      // Only restore if the file is actually inside the working copy
      if (!brokenReal.startsWith(workRootReal)) {
        return 0;
      }

      Path relative = workRootReal.relativize(brokenReal);
      Path original = originalSrcRoot.resolve(relative).normalize();

      if (!Files.isRegularFile(original)) {
        return 0; // no original to restore from
      }

      Path parent = brokenReal.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(
          original,
          brokenReal,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES);

      System.out.println("[DP] Restored original source for " + brokenReal);
      return 1;
    } catch (Exception ex) {
      System.err.println("[DP] Failed to restore " + brokenFile + ": " + ex.getMessage());
      return 0;
    }
  }

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
    if (home != null && !home.isBlank()) {
      Path p = Path.of(home, "bin", base + ext);
      if (Files.isRegularFile(p)) return p.toString();
    }
    return base + ext;
  }

  private static boolean isWin() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

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

    // Run inside the instrumented working copy
    pb.directory(workDir.toFile());

    // Export classpath for the script
    Map<String, String> env = pb.environment();
    env.put("DP_DAIKONPP_CLASSPATH", fullRunCp);
    env.put("DP_RUN_LOG", runLog.toAbsolutePath().toString());

    Path invDir = workDir.resolve(".daikonpp-events");
    Files.createDirectories(invDir);

    // 1️⃣ Keep env var (harmless, useful for debugging)
    env.put("DP_INV_DIR", invDir.toAbsolutePath().toString());

    // 2️⃣ CRITICAL: force JVM args for *plain java* commands
    String jvmArgs = "-DDP_INV_DIR=" + invDir.toAbsolutePath();

    // If script uses JAVA_OPTS (very common)
    env.put("JAVA_OPTS", (env.getOrDefault("JAVA_OPTS", "") + " " + jvmArgs).trim());

    // If script uses _JAVA_OPTIONS (also common)
    env.put("_JAVA_OPTIONS", (env.getOrDefault("_JAVA_OPTIONS", "") + " " + jvmArgs).trim());

    // If script uses Gradle
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
        // stream output LIVE
        while (r.ready() && (line = r.readLine()) != null) {
          w.write(line);
          w.newLine();
          w.flush(); // 🔥 THIS is why you used to see test names
        }

        if (!p.isAlive()) break;

        if (System.nanoTime() > deadline) {
          w.write(
              "[DP] External runner TIMED OUT after " + EXTERNAL_RUN_TIMEOUT_MINUTES + " minutes");
          w.newLine();
          w.flush();

          try {
            p.getOutputStream().close();
          } catch (Exception ignored) {
          }
          p.destroyForcibly();
          break;
        }

        Thread.sleep(100);
      }
    }

    // ---- NORMAL EXIT ----
    int exit = p.isAlive() ? -1 : p.exitValue();

    appendDpEvents(invDir, runLog);

    if (exit != 0) {
      Files.writeString(
          runLog,
          "\n[DP] External runner exited with code " + exit + "\n",
          StandardOpenOption.APPEND);
    }
  }

  private static void appendDpEvents(Path invDir, Path runLog) {
    try {
      if (!Files.isDirectory(invDir)) return;

      // append in a deterministic order
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

      if (files.isEmpty()) return;

      Files.writeString(
          runLog,
          "\n[DP] === invariant events (merged) ===\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);

      for (Path f : files) {
        Files.writeString(
            runLog, Files.readString(f, StandardCharsets.UTF_8), StandardOpenOption.APPEND);
      }

    } catch (Exception ignored) {
    }
  }
}
