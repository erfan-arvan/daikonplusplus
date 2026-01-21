package edu.njit.jerse.daikonplusplus.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted invariant instance with unique ID and provenance information. */
public final class InvariantRecord {
  private final UUID id;
  private final InvariantSpec spec;
  private final ProgramPoint point;
  private final String sourceFile;
  private final Instant createdAt;
  

  public InvariantRecord(
      UUID id, InvariantSpec spec, ProgramPoint point, String sourceFile, Instant createdAt) {
    this.id = Objects.requireNonNull(id);
    this.spec = Objects.requireNonNull(spec);
    this.point = Objects.requireNonNull(point);
    this.sourceFile = Objects.requireNonNull(sourceFile);
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  public UUID id() {
    return id;
  }

  public InvariantSpec spec() {
    return spec;
  }

  public ProgramPoint point() {
    return point;
  }

  public String sourceFile() {
    return sourceFile;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** human readable label: {id} a.b.C#m(int):void [METHOD_ENTRY] -> expr */
  public String humanLabel() {
    return id + " " + point.elementId() + " [" + point.kind() + "] -> " + spec.expression();
  }
}
