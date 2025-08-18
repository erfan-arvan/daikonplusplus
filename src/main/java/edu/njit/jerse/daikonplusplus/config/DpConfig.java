package edu.njit.jerse.daikonplusplus.config;

import java.nio.file.Path;

/**
 * Central configuration for Daikon++.
 *
 * <p>Keep this small and immutable; load from env/system props or a config file.
 */
public final class DpConfig {
  private final int threads;
  private final Path registryPath;

  public DpConfig(int threads, Path registryPath) {
    this.threads = threads;
    this.registryPath = registryPath;
  }

  /** Number of worker threads for parallel processing. */
  public int threads() {
    return threads;
  }

  /** Path to the JSONL registry (one invariant record per line). */
  public Path registryPath() {
    return registryPath;
  }

  /** Convenience factory reading sane defaults from environment variables. */
  public static DpConfig fromEnv() {
    int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
    Path reg = Path.of(System.getProperty("dp.registry", "build/daikonpp_registry.jsonl"));
    return new DpConfig(threads, reg);
  }
}
