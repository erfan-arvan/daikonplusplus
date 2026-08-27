package edu.njit.jerse.daikonplusplus.inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the {@code daikonpp.DpRuntime} helper class into a source tree so injected invariant code
 * can compile without referencing {@code System.getProperties()}.
 *
 * <p>DpRuntime uses /dev/shm (or a configured DP_SHM_DIR) to persist invariant execution and
 * failure events as files so they survive SIGKILL. On JVM startup, existing shm files are loaded
 * into SEEN/SEEN_FAIL so already-checked invariants are skipped on rerun (recovery). No events are
 * written to stdout — the shm directory is the sole record during a live run.
 *
 * <p>Fallback: when DP_SHM_DIR is not set, SHM_EX_DIR/SHM_FAIL_DIR/SHM_CURRENT_DIR are null. In
 * that case results are persisted only via the shutdown-hook sidecar written to DP_INV_DIR (picked
 * up by JavaRunner.appendDpEvents after the JVM exits normally).
 */
public final class DpRuntimeWriter {

  private DpRuntimeWriter() {}

  /**
   * Writes {@code daikonpp/DpRuntime.java} under {@code srcRoot}.
   *
   * @param srcRoot root of the source tree to receive the helper
   * @throws IOException if the file cannot be written
   */
  public static void write(Path srcRoot) throws IOException {
    Path pkg = srcRoot.resolve("daikonpp");
    Files.createDirectories(pkg);
    writeNullMarkingPackageInfoIfRequested(pkg);
    Path file = pkg.resolve("DpRuntime.java");
    String src =
        "package daikonpp;\n"
            + "import java.util.concurrent.ConcurrentHashMap;\n"
            + "import java.util.concurrent.atomic.AtomicBoolean;\n"
            + "public final class DpRuntime {\n"
            // --- shm dirs (null when DP_SHM_DIR not set) ---
            + "    public static final java.nio.file.Path SHM_EX_DIR;\n"
            + "    public static final java.nio.file.Path SHM_FAIL_DIR;\n"
            + "    public static final java.nio.file.Path SHM_CURRENT_DIR;\n"
            // --- in-memory dedup sets ---
            + "    public static final java.util.Set<String> SEEN =\n"
            + "        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "    public static final java.util.Set<String> SEEN_FAIL =\n"
            + "        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            // --- re-entrancy guard (per-thread) ---
            + "    public static final ThreadLocal<AtomicBoolean> GUARD =\n"
            + "        ThreadLocal.withInitial(() -> new AtomicBoolean(false));\n"
            // --- disabled invariants ---
            + "    public static final java.util.Set<String> DISABLED = loadDisabled();\n"
            // --- DP_INV_DIR for shutdown-hook sidecar fallback ---
            + "    public static final String INV_DIR = System.getProperty(\"DP_INV_DIR\");\n"
            // --- static init: wire shm dirs and pre-populate SEEN from existing files ---
            + "    static {\n"
            + "        String shmBase = System.getProperty(\"DP_SHM_DIR\");\n"
            + "        if (shmBase == null) shmBase = System.getenv(\"DP_SHM_DIR\");\n"
            + "        java.nio.file.Path exDir = null;\n"
            + "        java.nio.file.Path failDir = null;\n"
            + "        java.nio.file.Path currentDir = null;\n"
            + "        if (shmBase != null && !shmBase.trim().isEmpty()) {\n"
            + "            try {\n"
            + "                java.nio.file.Path base = java.nio.file.Paths.get(shmBase);\n"
            + "                exDir = base.resolve(\"ex\");\n"
            + "                failDir = base.resolve(\"fail\");\n"
            + "                currentDir = base.resolve(\"current\");\n"
            + "                java.nio.file.Files.createDirectories(exDir);\n"
            + "                java.nio.file.Files.createDirectories(failDir);\n"
            + "                java.nio.file.Files.createDirectories(currentDir);\n"
            // pre-populate SEEN from existing ex/ files (SIGKILL recovery)
            + "                final java.nio.file.Path fEx = exDir;\n"
            + "                try (java.util.stream.Stream<java.nio.file.Path> s =\n"
            + "                        java.nio.file.Files.list(fEx)) {\n"
            + "                    s.forEach(p -> {\n"
            + "                        java.nio.file.Path fn = p.getFileName();\n"
            + "                        if (fn != null) SEEN.add(fn.toString());\n"
            + "                    });\n"
            + "                }\n"
            // pre-populate SEEN_FAIL from existing fail/ files
            + "                final java.nio.file.Path fFail = failDir;\n"
            + "                try (java.util.stream.Stream<java.nio.file.Path> s =\n"
            + "                        java.nio.file.Files.list(fFail)) {\n"
            + "                    s.forEach(p -> {\n"
            + "                        java.nio.file.Path fn = p.getFileName();\n"
            + "                        if (fn == null) return;\n"
            + "                        String name = fn.toString();\n"
            + "                        if (name.endsWith(\".json\"))\n"
            + "                            SEEN_FAIL.add(name.substring(0, name.length() - 5));\n"
            + "                    });\n"
            + "                }\n"
            + "            } catch (Exception ignored) {}\n"
            + "        }\n"
            // register shutdown-hook sidecar fallback (fires only when JVM exits normally)
            + "        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {\n"
            + "            public void run() {\n"
            + "                try {\n"
            + "                    String invDir = INV_DIR;\n"
            + "                    if (invDir == null || invDir.trim().length() == 0) return;\n"
            + "                    java.io.File dir = new java.io.File(invDir);\n"
            + "                    dir.mkdirs();\n"
            + "                    java.io.File out = new java.io.File(dir,\n"
            + "                        \"dp-events-\" + java.util.UUID.randomUUID() + \".log\");\n"
            + "                    StringBuilder sb = new StringBuilder();\n"
            + "                    for (String k : SEEN) {\n"
            + "                        sb.append(\"INV_EXD:\").append(k).append('\\n');\n"
            + "                    }\n"
            + "                    if (SHM_FAIL_DIR != null) {\n"
            + "                        try (java.util.stream.Stream<java.nio.file.Path> s =\n"
            + "                                java.nio.file.Files.list(SHM_FAIL_DIR)) {\n"
            + "                            s.forEach(p -> {\n"
            + "                                try {\n"
            + "                                    String content = new String(\n"
            + "                                        java.nio.file.Files.readAllBytes(p),\n"
            + "                                        java.nio.charset.StandardCharsets.UTF_8);\n"
            + "                                    if (!content.trim().isEmpty())\n"
            + "                                        sb.append(content.trim()).append('\\n');\n"
            + "                                } catch (Exception __ig) {}\n"
            + "                            });\n"
            + "                        } catch (Exception __ig) {}\n"
            + "                    }\n"
            + "                    if (sb.length() > 0) {\n"
            + "                        java.io.OutputStream os = null;\n"
            + "                        try {\n"
            + "                            os = new java.io.FileOutputStream(out, true);\n"
            + "                            os.write(sb.toString().getBytes(\"UTF-8\"));\n"
            + "                        } finally {\n"
            + "                            if (os != null) try { os.close(); } catch (Throwable t) {}\n"
            + "                        }\n"
            + "                    }\n"
            + "                } catch (Throwable __ignore) {}\n"
            + "            }\n"
            + "        }, \"dp-sidecar-flush\"));\n"
            + "        SHM_EX_DIR = exDir;\n"
            + "        SHM_FAIL_DIR = failDir;\n"
            + "        SHM_CURRENT_DIR = currentDir;\n"
            + "    }\n"
            // --- loadDisabled ---
            + "    private static java.util.Set<String> loadDisabled() {\n"
            + "        java.util.Set<String> s =\n"
            + "            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "        String f = System.getProperty(\"DP_DISABLED_FILE\");\n"
            + "        if (f == null || f.trim().isEmpty()) f = System.getenv(\"DP_DISABLED_FILE\");\n"
            + "        if (f != null && !f.trim().isEmpty()) {\n"
            + "            try {\n"
            + "                java.nio.file.Path p = java.nio.file.Paths.get(f);\n"
            + "                if (java.nio.file.Files.exists(p)) {\n"
            + "                    for (String line : java.nio.file.Files.readAllLines(p)) {\n"
            + "                        if (line != null && !line.trim().isEmpty()) s.add(line.trim());\n"
            + "                    }\n"
            + "                }\n"
            + "            } catch (Exception ignored) {}\n"
            + "        }\n"
            + "        return s;\n"
            + "    }\n"
            // --- recordExecuted: write shm/ex/<uuid>, no stdout ---
            // Empty marker file; execution order is read from the file's OS-assigned
            // creation/modified timestamp, not from any content written here.
            + "    public static void recordExecuted(String uuid) {\n"
            + "        if (SEEN.add(uuid) && SHM_EX_DIR != null) {\n"
            + "            try {\n"
            + "                java.nio.file.Files.createFile(SHM_EX_DIR.resolve(uuid));\n"
            + "            } catch (Exception __ignore) {}\n"
            + "        }\n"
            + "    }\n"
            // --- markCurrent: write shm/current/<uuid> before evaluation ---
            + "    public static void markCurrent(String uuid) {\n"
            + "        if (SHM_CURRENT_DIR != null) {\n"
            + "            try {\n"
            + "                java.nio.file.Files.createFile(SHM_CURRENT_DIR.resolve(uuid));\n"
            + "            } catch (Exception __ignore) {}\n"
            + "        }\n"
            + "    }\n"
            // --- clearCurrent: remove shm/current/<uuid> after evaluation ---
            + "    public static void clearCurrent(String uuid) {\n"
            + "        if (SHM_CURRENT_DIR != null) {\n"
            + "            try {\n"
            + "                java.nio.file.Files.deleteIfExists(SHM_CURRENT_DIR.resolve(uuid));\n"
            + "            } catch (Exception __ignore) {}\n"
            + "        }\n"
            + "    }\n"
            // --- recordFailed: write shm/fail/<uuid>.json, no stdout ---
            + "    public static void recordFailed(String uuid, String json) {\n"
            + "        if (SEEN_FAIL.add(uuid) && SHM_FAIL_DIR != null) {\n"
            + "            try {\n"
            + "                java.nio.file.Files.write(\n"
            + "                    SHM_FAIL_DIR.resolve(uuid + \".json\"),\n"
            + "                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8),\n"
            + "                    java.nio.file.StandardOpenOption.CREATE,\n"
            + "                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);\n"
            + "            } catch (Exception __ignore) {}\n"
            + "        }\n"
            + "    }\n"
            + "    private DpRuntime() {}\n"
            + "}\n";
    Files.writeString(file, src, StandardCharsets.UTF_8);
    System.out.println("[DP] Wrote DpRuntime helper → " + file);
  }

  /**
   * Some target projects (e.g. spring-framework) put {@code org.jspecify:jspecify} on the compile
   * classpath, which causes an auto-discovered annotation processor to require every top-level
   * class to be explicitly null-marked (directly, via package, or via module) -- our generated
   * {@code daikonpp} package has none of those, so {@code DpRuntime.java} fails to compile with
   * {@code [RequireExplicitNullMarking]}, and the autofilter can't self-repair it (it isn't
   * instrumented target code with an "original" to restore).
   *
   * <p>Unconditionally importing {@code org.jspecify.annotations.NullUnmarked} would break any
   * project that doesn't have jspecify on its classpath (Dubbo, Netty, ...) with a hard "package
   * does not exist" error, so this is opt-in via {@code DP_JSPECIFY_NULL_MARKING=true}, which the
   * external-project driver script sets only for projects known to require it.
   *
   * @param pkg the {@code daikonpp} package directory to receive {@code package-info.java}
   * @throws IOException if the file cannot be written
   */
  private static void writeNullMarkingPackageInfoIfRequested(Path pkg) throws IOException {
    String flag = System.getenv("DP_JSPECIFY_NULL_MARKING");
    if (flag == null || !flag.trim().equalsIgnoreCase("true")) {
      return;
    }
    Path file = pkg.resolve("package-info.java");
    String src =
        "@org.jspecify.annotations.NullUnmarked\n"
            + "package daikonpp;\n";
    Files.writeString(file, src, StandardCharsets.UTF_8);
    System.out.println("[DP] Wrote JSpecify null-marking package-info → " + file);
  }
}
