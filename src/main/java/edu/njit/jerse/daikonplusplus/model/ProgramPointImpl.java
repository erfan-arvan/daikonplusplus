package edu.njit.jerse.daikonplusplus.model;

import java.util.Objects;

/** Simple immutable implementation of {@link ProgramPoint}. */
public final class ProgramPointImpl implements ProgramPoint {
  private final ProgramElementId id;
  private final ProgramPointKind kind;

  public ProgramPointImpl(ProgramElementId id, ProgramPointKind kind) {
    this.id = Objects.requireNonNull(id);
    this.kind = Objects.requireNonNull(kind);
  }

  @Override
  public ProgramElementId elementId() {
    return id;
  }

  @Override
  public ProgramPointKind kind() {
    return kind;
  }
}
