package edu.njit.jerse.daikonplusplus.model;

/** Abstraction of a program point within the source code. */
public interface ProgramPoint {
  /** Stable identity of the underlying program element. */
  ProgramElementId elementId();

  /** Kind of program point. */
  ProgramPointKind kind();
}
