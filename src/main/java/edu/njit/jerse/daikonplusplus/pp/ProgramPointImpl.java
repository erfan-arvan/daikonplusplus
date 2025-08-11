package edu.njit.jerse.daikonplusplus.pp;

import edu.njit.jerse.daikonplusplus.inv.Invariant;
import edu.njit.jerse.daikonplusplus.structure.ProgramStructureElement;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Concrete implementation of a program point. */
public class ProgramPointImpl implements ProgramPoint {

  private final ProgramStructureElement parent;
  private final ProgramPointKind kind;
  private final int lineNumber;
  private final List<VariableInfo> visibleVariables;
  private final List<Invariant> invariants;
  private final String uniqueId;

  public ProgramPointImpl(
      ProgramStructureElement parent,
      ProgramPointKind kind,
      int lineNumber,
      List<VariableInfo> visibleVariables,
      List<Invariant> invariants) {
    this.parent = parent;
    this.kind = kind;
    this.lineNumber = lineNumber;
    this.visibleVariables = visibleVariables;
    this.invariants = invariants;
    this.uniqueId = computeUniqueId();
  }

  @RequiresNonNull({"parent", "kind"})
  private String computeUniqueId(@UnderInitialization ProgramPointImpl this) {
    return parent.getUniqueId() + ":::" + kind.name() + "@L" + lineNumber;
  }

  @Override
  public ProgramStructureElement getParentElement() {
    return parent;
  }

  @Override
  public ProgramPointKind getKind() {
    return kind;
  }

  @Override
  public String getUniqueId() {
    return uniqueId;
  }

  @Override
  public int getLineNumber() {
    return lineNumber;
  }

  @Override
  public String getFilePath() {
    return parent.getFilePath();
  }

  @Override
  public List<VariableInfo> getVisibleVariables() {
    return Collections.unmodifiableList(visibleVariables);
  }

  @Override
  public List<Invariant> getInvariants() {
    return Collections.unmodifiableList(invariants);
  }

  @Override
  public String toString() {
    return kind + " @ " + parent.getFullyQualifiedName() + " line " + lineNumber;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) return true;
    if (!(o instanceof ProgramPointImpl other)) return false;
    return Objects.equals(this.uniqueId, other.uniqueId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uniqueId);
  }
}
