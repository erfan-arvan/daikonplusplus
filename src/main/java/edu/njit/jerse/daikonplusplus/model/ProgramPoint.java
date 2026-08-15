package edu.njit.jerse.daikonplusplus.model;

/** Abstraction of a program point within the source code. */
public interface ProgramPoint {
  /** stable identity of the underlying program element. */
  ProgramElementId elementId();

  /** kind of program point. */
  ProgramPointKind kind();
}
