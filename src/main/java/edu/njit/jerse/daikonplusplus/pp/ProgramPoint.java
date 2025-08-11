package edu.njit.jerse.daikonplusplus.pp;

import edu.njit.jerse.daikonplusplus.inv.Invariant;
import edu.njit.jerse.daikonplusplus.structure.ProgramStructureElement;
import java.util.List;

/**
 * Represents a specific semantic point in the program where invariants may be inferred or checked.
 */
public interface ProgramPoint {

  /** The parent program structure element this point belongs to. */
  ProgramStructureElement getParentElement();

  /** The kind of this program point (e.g., METHOD_ENTRY, LOOP_HEADER). */
  ProgramPointKind getKind();

  /** Unique ID for this program point (e.g., elementUniqueId + kind + line). */
  String getUniqueId();

  /** Line number in the file where this point occurs. */
  int getLineNumber();

  /** Path to the file containing this program point. */
  String getFilePath();

  /** Variables visible at this point (e.g., method parameters, fields, locals). */
  List<VariableInfo> getVisibleVariables();

  /** Invariants associated with this point. */
  List<Invariant> getInvariants();
}
