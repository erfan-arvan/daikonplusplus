package edu.njit.jerse.daikonplusplus.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Prints local-system-time timestamps marking the start and finish of each pipeline phase (LLM
 * proposal, injection, compilation, execution, results parsing, etc.), following the existing
 * {@code >>> ...} logging convention used throughout {@code App.java}.
 */
public final class PhaseTimer {

  private static final DateTimeFormatter FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private PhaseTimer() {}

  /** Logs that {@code phaseName} started now (local system time) and returns that instant. */
  public static Instant start(String phaseName) {
    Instant now = Instant.now();
    System.out.println(">>> [" + phaseName + "] started at " + format(now));
    return now;
  }

  /**
   * Logs that {@code phaseName} finished now (local system time), with elapsed time since {@code
   * startedAt}.
   */
  public static Instant finish(String phaseName, Instant startedAt) {
    Instant now = Instant.now();
    System.out.println(
        ">>> ["
            + phaseName
            + "] finished at "
            + format(now)
            + " (elapsed "
            + formatDuration(Duration.between(startedAt, now))
            + ")");
    return now;
  }

  private static String format(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(FMT);
  }

  private static String formatDuration(Duration d) {
    return String.format("%.3fs", d.toMillis() / 1000.0);
  }
}
