package edu.njit.jerse.daikonplusplus.structure;

import java.util.List;

/**
 * Represents a constructor in the program structure hierarchy. Stores the parameter types for
 * signature generation.
 */
public class ConstructorElement extends ProgramStructureElement {

  /** The parameter types of the constructor. */
  private final List<String> paramTypes;

  /**
   * Constructs a new constructor element.
   *
   * @param simpleName The constructor's name (usually matches class name).
   * @param paramTypes The fully qualified types of each parameter.
   * @param parent The class containing this constructor.
   * @param filePath Path to the file where the constructor is declared.
   * @param startLine The line where the constructor declaration begins.
   * @param endLine The line where the constructor declaration ends.
   */
  public ConstructorElement(
      String simpleName,
      List<String> paramTypes,
      ProgramStructureElement parent,
      String filePath,
      int startLine,
      int endLine) {
    super(
        simpleName,
        ProgramElementType.CONSTRUCTOR,
        parent,
        filePath,
        startLine,
        endLine,
        buildSig(paramTypes)); // pass signature for uniqueId
    this.paramTypes = paramTypes;
  }

  /** Builds a signature string like {@code (int,String)} for uniqueId. */
  private static String buildSig(List<String> params) {
    String joined = (params == null || params.isEmpty()) ? "" : String.join(",", params);
    return "(" + joined + ")";
  }

  /** Returns the list of parameter types. */
  public List<String> getParamTypes() {
    return paramTypes;
  }
}
