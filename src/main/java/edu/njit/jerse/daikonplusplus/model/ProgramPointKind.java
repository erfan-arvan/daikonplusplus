package edu.njit.jerse.daikonplusplus.model;

/**
 * Kinds of program points where invariants may be evaluated.
 *
 * <p>Only METHOD_ENTRY is supported in this iteration.
 */
public enum ProgramPointKind {
  METHOD_ENTRY,
  METHOD_EXIT
  // Future: LOOP_HEADER, LOOP_EXIT, FIELD, etc.
}
