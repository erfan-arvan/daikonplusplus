package edu.njit.jerse.daikonplusplus.structure;

/** Represents a class, interface, or enum in the program structure hierarchy. */
public class ClassElement extends ProgramStructureElement {

  /**
   * Constructs a new class element.
   *
   * @param simpleName The class's simple (unqualified) name.
   * @param parent The package or outer class containing this class.
   * @param filePath Path to the file where the class is declared.
   * @param startLine The line where the class declaration begins.
   * @param endLine The line where the class declaration ends.
   */
  public ClassElement(
      String simpleName,
      ProgramStructureElement parent,
      String filePath,
      int startLine,
      int endLine) {
    super(simpleName, ProgramElementType.CLASS, parent, filePath, startLine, endLine, null);
  }
}
