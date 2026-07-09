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
            + "    public static final String INV_DIR = System.getProperty(\"DP_INV_DIR\");\n"
            + "    public static final boolean ENABLED = true;\n"
            + "    public static final ConcurrentHashMap<String,Boolean> EXECUTED = new ConcurrentHashMap<>();\n"
            + "    public static final ConcurrentHashMap<String,String> FAIL_JSON = new ConcurrentHashMap<>();\n"
            + "    public static final ThreadLocal<AtomicBoolean> GUARD = ThreadLocal.withInitial(() -> new AtomicBoolean(false));\n"
            + "    public static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);\n"
            + "    public static final java.util.Set<String> DISABLED = loadDisabled();\n"
            + "    private static java.util.Set<String> loadDisabled() {\n"
            + "        java.util.Set<String> s = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());\n"
            + "        String f = System.getProperty(\"DP_DISABLED_FILE\");\n"
            + "        if (f == null || f.isBlank()) f = System.getenv(\"DP_DISABLED_FILE\");\n"
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
