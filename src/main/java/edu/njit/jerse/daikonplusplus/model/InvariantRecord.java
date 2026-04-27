package edu.njit.jerse.daikonplusplus.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a generated invariant along with its associated program point and metadata.
 *
 * <p>Each record has a unique identifier and captures the invariant specification, its location in
 * the program, and creation time.
 */
public final class InvariantRecord {
  private final UUID id;
  private final InvariantSpec spec;
  private final ProgramPoint point;
  private final String sourceFile;
  private final Instant createdAt;

  /**
   * Creates a new invariant record.
   *
   * @param id unique identifier of the invariant
   * @param spec invariant specification
   * @param point program point where the invariant is defined
   * @param sourceFile relative path of the source file
   * @param createdAt creation timestamp
   */
  public InvariantRecord(
      UUID id, InvariantSpec spec, ProgramPoint point, String sourceFile, Instant createdAt) {
    this.id = Objects.requireNonNull(id);
    this.spec = Objects.requireNonNull(spec);
    this.point = Objects.requireNonNull(point);
    this.sourceFile = Objects.requireNonNull(sourceFile);
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  /**
   * Returns the unique identifier of this invariant.
   *
   * @return invariant ID
   */
  public UUID id() {
    return id;
  }

  /**
   * Returns the invariant specification.
   *
   * @return invariant specification
   */
  public InvariantSpec spec() {
    return spec;
  }

  /**
   * Returns the program point associated with this invariant.
   *
   * @return program point
   */
  public ProgramPoint point() {
    return point;
  }

  /**
   * Returns the source file where the invariant was generated.
   *
   * @return relative source file path
   */
  public String sourceFile() {
    return sourceFile;
  }

  /**
   * Returns the creation time of this invariant.
   *
   * @return creation timestamp
   */
  public Instant createdAt() {
    return createdAt;
  }

  /** human readable label: {id} a.b.C#m(int):void [METHOD_ENTRY] -> expr */
  public String humanLabel() {
    return id + " " + point.elementId() + " [" + point.kind() + "] -> " + spec.expression();
  }
}
