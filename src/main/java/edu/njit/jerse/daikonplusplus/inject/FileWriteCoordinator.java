package edu.njit.jerse.daikonplusplus.inject;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates concurrent writes to files using per-file locks.
 */
public final class FileWriteCoordinator {
  private final ConcurrentMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

  /**
   * Executes an action while holding a lock for a specific file.
   *
   * @param p file path
   * @param action operation to execute
   * @return result of the action
   * @throws Exception if the action fails
   */
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
