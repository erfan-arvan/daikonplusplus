package edu.njit.jerse.daikonplusplus.inject;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Protects concurrent writes to the same file using per-file locks. */
public final class FileWriteCoordinator {
  private final ConcurrentMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

  public <T> T withFileLock(Path p, Callable<T> action) throws Exception {
    var lock = locks.computeIfAbsent(p, __ -> new ReentrantLock());
    lock.lock();
    try {
      return action.call();
    } finally {
      lock.unlock();
    }
  }
}
