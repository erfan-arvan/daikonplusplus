package edu.njit.jerse.daikonplusplus.runtime;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InvariantExecTracker {
  private static final ConcurrentHashMap<UUID, Boolean> seen = new ConcurrentHashMap<>();

  private InvariantExecTracker() {}

  public static void executed(String id) {
    UUID u;
    try {
      u = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return; // ignore malformed
    }
    if (seen.putIfAbsent(u, Boolean.TRUE) == null) {
      System.out.println("INV_EXD:" + id); // printed once per invariant per JVM
    }
  }
}
