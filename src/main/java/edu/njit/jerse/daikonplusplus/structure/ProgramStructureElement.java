package edu.njit.jerse.daikonplusplus.structure;

import edu.njit.jerse.daikonplusplus.pp.ProgramPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a structural element in a Java program, such as a class, method, field, or block.
 * These elements form a tree hierarchy reflecting the program's structure. Each node may contain:
 *
 * <ul>
 *   <li>Other nested structure elements (children)
 *   <li>Associated program points (e.g., method entry, loop heads) for invariant generation
 *   <li>Location metadata such as file path and source code line range
 * </ul>
 */
public abstract class ProgramStructureElement {

  /** Local name of the element (e.g., "toString", "MyClass", "count") */
  protected final String identifier;

  /** Enum value indicating whether this is a class, method, field, etc. */
  protected final ProgramElementType elementType;

  /** Parent structure element in the hierarchy, or null if this is the root. */
  protected final @Nullable ProgramStructureElement parent;

  /** Child elements nested within this one (e.g., methods in a class). */
  protected final List<ProgramStructureElement> children;

  /** Program points associated with this structure element (e.g., method entry, loop heads). */
  protected final List<ProgramPoint> programPoints;

  /** Path to the source file that defines this element. */
  protected final String filePath;

  /** Line where the element begins in the source file (inclusive). */
  protected final int startLine;

  /** Line where the element ends in the source file (inclusive). */
  protected final int endLine;

  /** Fully qualified name including parent hierarchy (e.g., foo.Bar.toString). */
  protected final String fullyQualifiedName;

  /** Globally unique ID including file, FQN, type, and optionally a signature. */
  protected final String uniqueId;

  /**
   * Constructs a new program structure element.
   *
   * @param identifier Short name for this element (e.g., "main", "count").
   * @param elementType The type of the element (CLASS, METHOD, FIELD, etc.).
   * @param parent The parent element in the hierarchy, or null if top-level.
   * @param filePath Path to the source file where this element is defined.
   * @param startLine Start line in the source file (inclusive).
   * @param endLine End line in the source file (inclusive).
   */
  @SuppressWarnings("initialization")
  public ProgramStructureElement(
      String identifier,
      ProgramElementType elementType,
      @Nullable ProgramStructureElement parent,
      String filePath,
      int startLine,
      int endLine,
      @Nullable String signatureForId) {
    this.identifier = identifier;
    this.elementType = elementType;
    this.parent = parent;
    this.filePath = filePath;
    this.startLine = startLine;
    this.endLine = endLine;
    this.children = new ArrayList<>();
    this.programPoints = new ArrayList<>();

    this.fullyQualifiedName = computeFullyQualifiedName();
    this.uniqueId = computeUniqueId(signatureForId);

    if (parent != null) {
      parent.addChild(this);
    }
  }

  /** Returns the local identifier (e.g., "main", "count"). */
  public String getIdentifier() {
    return identifier;
  }

  /** Returns the enum type of this program element (e.g., METHOD, CLASS). */
  public ProgramElementType getElementType() {
    return elementType;
  }

  /** Returns the parent element in the program structure hierarchy, or null if this is the root. */
  public @Nullable ProgramStructureElement getParent() {
    return parent;
  }

  /** Returns an unmodifiable list of all direct children of this structure element. */
  public List<ProgramStructureElement> getChildren() {
    return Collections.unmodifiableList(children);
  }

  /** Returns an unmodifiable list of all program points associated with this element. */
  public List<ProgramPoint> getProgramPoints() {
    return Collections.unmodifiableList(programPoints);
  }

  /** Returns the absolute or project-relative path to the file where this element is defined. */
  public String getFilePath() {
    return filePath;
  }

  /** Returns the line number in the file where this element begins (inclusive). */
  public int getStartLine() {
    return startLine;
  }

  /** Returns the line number in the file where this element ends (inclusive). */
  public int getEndLine() {
    return endLine;
  }

  /**
   * Returns the fully qualified name of the element, including all parent names. For example:
   * {@code foo.Bar.toString}
   */
  public String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  /**
   * Returns a globally unique ID for this structure element. This includes file path, fully
   * qualified name, element type, and (if applicable) a signature. Example: {@code
   * src/Foo.java#foo.Bar.toString:METHOD:void(String)}
   */
  public String getUniqueId() {
    return uniqueId;
  }

  /**
   * Adds a child structure element to this element.
   *
   * @param child The element to add as a child.
   */
  public void addChild(@UnderInitialization ProgramStructureElement child) {
    @SuppressWarnings("cast.unsafe")
    ProgramStructureElement initChild = (@Initialized ProgramStructureElement) child;
    children.add(initChild);
  }

  /**
   * Adds a program point to this structure element (e.g., method entry or exit).
   *
   * @param pp The program point to associate.
   */
  public void addProgramPoint(ProgramPoint pp) {
    programPoints.add(pp);
  }

  /** Computes the fully qualified name by walking up the parent chain. */
  protected String computeFullyQualifiedName(@UnderInitialization ProgramStructureElement this) {
    return (parent != null ? parent.getFullyQualifiedName() + "." : "") + identifier;
  }

  /**
   * Computes a globally unique identifier string. This combines file path, fully qualified name,
   * element type, and an optional signature.
   */
  protected String computeUniqueId(
      @UnderInitialization ProgramStructureElement this, @Nullable String signatureForId) {

    StringBuilder sb = new StringBuilder();
    sb.append(filePath)
        .append("#")
        .append(fullyQualifiedName)
        .append(":")
        .append(String.valueOf(elementType)); // no dereference here

    if (signatureForId != null && !signatureForId.isEmpty()) {
      sb.append(":").append(signatureForId);
    }
    return sb.toString();
  }

  /**
   * Returns a method or constructor signature (e.g., {@code void(String)}). By default, returns an
   * empty string. Subclasses should override as needed.
   */
  protected String getSignature() {
    return "";
  }

  /**
   * Returns a human-readable string including type, fully qualified name, and source line range.
   */
  @Override
  public String toString() {
    return elementType + ": " + fullyQualifiedName + " [" + startLine + "-" + endLine + "]";
  }

  /** Checks equality based on the unique ID of the structure element. */
  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) return true;
    if (!(o instanceof ProgramStructureElement other)) return false;
    return Objects.equals(this.uniqueId, other.uniqueId);
  }

  /** Returns a hash code based on the unique ID. */
  @Override
  public int hashCode() {
    return Objects.hash(uniqueId);
  }
}
