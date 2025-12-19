package edu.njit.jerse.daikonplusplus.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DpRuntimeLog {

  private static final AtomicBoolean INIT = new AtomicBoolean(false);

  // executed IDs
  private static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();

  // id -> JSON fail line (first one wins)
  private static final Map<String, String> FAILED = new ConcurrentHashMap<>();

  /** record that invariant executed */
  public static void exd(String id) {
    ensureInit();
    EXECUTED.add(id);
  }

  /** record that invariant failed */
  public static void fail(String id, String jsonLine) {
    ensureInit();
    FAILED.putIfAbsent(id, jsonLine);
  }

  /** one-time shutdown hook */
  private static void ensureInit() {
    if (!INIT.compareAndSet(false, true)) return;

    Runtime.getRuntime()
        .addShutdownHook(new Thread(DpRuntimeLog::flushSafely, "daikonpp-log-flush"));
  }

  /** append results to daikonpp-run.log */
  private static void flushSafely() {
    try {
      flush();
    } catch (Throwable t) {
      // last-resort safety: never block JVM shutdown
      t.printStackTrace();
    }
  }

  private static void flush() throws IOException {
    String logPath = System.getProperty("DP_RUN_LOG");
    if (logPath == null || logPath.isBlank()) return;

    Path p = Path.of(logPath);

    try (BufferedWriter w =
        Files.newBufferedWriter(
            p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

      for (String id : EXECUTED) {
        w.write("INV_EXD:" + id);
        w.newLine();
      }
      for (String json : FAILED.values()) {
        w.write(json);
        w.newLine();
      }
    }
  }

  private DpRuntimeLog() {}
}
