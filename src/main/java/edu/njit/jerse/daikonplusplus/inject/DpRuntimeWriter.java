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
            + "    public static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);\n"
            + "    public static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);\n"
            + "    private DpRuntime() {}\n"
            + "}\n";
    Files.writeString(file, src, StandardCharsets.UTF_8);
    System.out.println("[DP] Wrote DpRuntime helper → " + file);
  }
}
