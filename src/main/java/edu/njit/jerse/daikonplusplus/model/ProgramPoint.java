package edu.njit.jerse.daikonplusplus.model;

/**
 * Represents a program point in the source code.
 *
 * <p>A program point identifies a specific location associated with a program element, such as
 * method entry or exit.
 */
public interface ProgramPoint {
  /**
   * Returns the identifier of the associated program element.
   *
   * @return program element identifier
   */
  ProgramElementId elementId();

  /**
   * Returns the kind of this program point.
   *
   * @return program point kind
   */
  ProgramPointKind kind();
}
