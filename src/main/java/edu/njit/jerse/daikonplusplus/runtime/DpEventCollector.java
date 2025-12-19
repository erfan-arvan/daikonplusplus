package edu.njit.jerse.daikonplusplus.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DpEventCollector {

  private static final AtomicBoolean INIT = new AtomicBoolean(false);

  // RAM only (per JVM)
  private static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();
  private static final Map<String, String> FAILED_JSON = new ConcurrentHashMap<>(); // id -> json

  public static void exd(String id) {
    ensureInit();
    EXECUTED.add(id);
  }

  public static void fail(String id, String json) {
    ensureInit();
    FAILED_JSON.putIfAbsent(id, json);
  }

  private static void ensureInit() {
    if (!INIT.compareAndSet(false, true)) return;
    Runtime.getRuntime().addShutdownHook(new Thread(DpEventCollector::flushSafely, "dp-flush"));
  }

  private static void flushSafely() {
    try {
      flushToSidecar();
    } catch (Throwable t) {
      // never block shutdown
    }
  }

  private static void flushToSidecar() throws IOException {
    String dir = System.getProperty("DP_INV_DIR");
    if (dir == null || dir.isBlank()) return;

    Path outDir = Path.of(dir);
    Files.createDirectories(outDir);

    String pid = pid();
    Path f = outDir.resolve("dp-events-" + pid + ".jsonl");

    try (BufferedWriter w =
        Files.newBufferedWriter(
            f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

      for (String id : EXECUTED) {
        w.write("INV_EXD:" + id);
        w.newLine();
      }
      for (String json : FAILED_JSON.values()) {
        w.write(json);
        w.newLine();
      }
    }
  }

  private static String pid() {
    // format is typically "<pid>@<hostname>"
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int at = name.indexOf('@');
    return (at > 0) ? name.substring(0, at) : name;
  }

  private DpEventCollector() {}
}
