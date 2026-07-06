package edu.njit.jerse.daikonplusplus.inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the {@code daikonpp.DpRuntime} helper class into a source tree so injected invariant code
 * can compile without referencing {@code System.getProperties()}.
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
    Path file = pkg.resolve("DpRuntime.java");
    String src =
        "package daikonpp;\n"
            + "import java.util.concurrent.ConcurrentHashMap;\n"
            + "import java.util.concurrent.atomic.AtomicBoolean;\n"
            + "public final class DpRuntime {\n"
            + "    public static final boolean ENABLED = true;\n"
            + "    public static final java.nio.file.Path SHM_EX_DIR;\n"
            + "    public static final java.nio.file.Path SHM_FAIL_DIR;\n"
            + "    public static final java.util.Set<String> SEEN =\n"
            + "        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "    public static final java.util.Set<String> SEEN_FAIL =\n"
            + "        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "    public static final ThreadLocal<AtomicBoolean> GUARD =\n"
            + "        ThreadLocal.withInitial(() -> new AtomicBoolean(false));\n"
            + "    public static final java.util.Set<String> DISABLED = loadDisabled();\n"
            + "    static {\n"
            + "        String shmBase = System.getProperty(\"DP_SHM_DIR\");\n"
            + "        if (shmBase == null) shmBase = System.getenv(\"DP_SHM_DIR\");\n"
            + "        java.nio.file.Path exDir = null;\n"
            + "        java.nio.file.Path failDir = null;\n"
            + "        if (shmBase != null && !shmBase.isBlank()) {\n"
            + "            try {\n"
            + "                java.nio.file.Path base = java.nio.file.Paths.get(shmBase);\n"
            + "                exDir = base.resolve(\"ex\");\n"
            + "                failDir = base.resolve(\"fail\");\n"
            + "                java.nio.file.Files.createDirectories(exDir);\n"
            + "                java.nio.file.Files.createDirectories(failDir);\n"
            + "                final java.nio.file.Path fExDir = exDir;\n"
            + "                try (java.util.stream.Stream<java.nio.file.Path> s =\n"
            + "                        java.nio.file.Files.list(fExDir)) {\n"
            + "                    s.forEach(p -> {\n"
            + "                        java.nio.file.Path fn = p.getFileName();\n"
            + "                        if (fn == null) return;\n"
            + "                        SEEN.add(fn.toString());\n"
            + "                    });\n"
            + "                }\n"
            + "                final java.nio.file.Path fFailDir = failDir;\n"
            + "                try (java.util.stream.Stream<java.nio.file.Path> s =\n"
            + "                        java.nio.file.Files.list(fFailDir)) {\n"
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
            + "        SHM_EX_DIR = exDir;\n"
            + "        SHM_FAIL_DIR = failDir;\n"
            + "    }\n"
            + "    public static void recordExecuted(String uuid) {\n"
            + "        if (SEEN.add(uuid)) {\n"
            + "            System.out.println(\"INV_EXD:\" + uuid);\n"
            + "            if (SHM_EX_DIR != null) {\n"
            + "                try {\n"
            + "                    java.nio.file.Files.createFile(SHM_EX_DIR.resolve(uuid));\n"
            + "                } catch (Exception __ignore) {}\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    public static void recordFailed(String uuid, String json) {\n"
            + "        if (SEEN_FAIL.add(uuid)) {\n"
            + "            System.out.println(json);\n"
            + "            if (SHM_FAIL_DIR != null) {\n"
            + "                try {\n"
            + "                    java.nio.file.Files.writeString(\n"
            + "                        SHM_FAIL_DIR.resolve(uuid + \".json\"), json);\n"
            + "                } catch (Exception __ignore) {}\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    private static java.util.Set<String> loadDisabled() {\n"
            + "        java.util.Set<String> s = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "        String f = System.getProperty(\"DP_DISABLED_FILE\");\n"
            + "        if (f != null && !f.isBlank()) {\n"
            + "            try {\n"
            + "                java.nio.file.Path p = java.nio.file.Paths.get(f);\n"
            + "                if (java.nio.file.Files.exists(p)) {\n"
            + "                    for (String line : java.nio.file.Files.readAllLines(p)) {\n"
            + "                        if (line != null && !line.isBlank()) s.add(line.trim());\n"
            + "                    }\n"
            + "                }\n"
            + "            } catch (Exception ignored) {}\n"
            + "        }\n"
            + "        return s;\n"
            + "    }\n"
            + "    private DpRuntime() {}\n"
            + "}\n";
    Files.writeString(file, src, StandardCharsets.UTF_8);
    System.out.println("[DP] Wrote DpRuntime helper → " + file);
  }
}
