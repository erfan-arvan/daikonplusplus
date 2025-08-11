package edu.njit.jerse.daikonplusplus.structure;

import java.util.List;

/**
 * Represents a method in the program structure hierarchy. Stores the return type and parameter
 * types for signature generation.
 */
public class MethodElement extends ProgramStructureElement {

  /** The return type of the method. */
  private final String returnType;

  /** The parameter types of the method. */
  private final List<String> paramTypes;

  /**
   * Constructs a new method element.
   *
   * @param simpleName The method's simple name.
   * @param returnType The return type of the method.
   * @param paramTypes The fully qualified types of each parameter.
   * @param parent The class or interface containing this method.
   * @param filePath Path to the file where the method is declared.
   * @param startLine The line where the method declaration begins.
   * @param endLine The line where the method declaration ends.
   */
  public MethodElement(
      String simpleName,
      String returnType,
      List<String> paramTypes,
      ProgramStructureElement parent,
      String filePath,
      int startLine,
      int endLine) {
    super(
        simpleName,
        ProgramElementType.METHOD,
        parent,
        filePath,
        startLine,
        endLine,
        buildSig(returnType, paramTypes)); // pass signature for uniqueId
    this.returnType = returnType;
    this.paramTypes = paramTypes;
  }

  /** Builds a signature string like {@code void(int,String)} for uniqueId. */
  private static String buildSig(String returnType, List<String> params) {
    String joined = (params == null || params.isEmpty()) ? "" : String.join(",", params);
    return returnType + "(" + joined + ")";
  }

  /** Returns the return type of this method. */
  public String getReturnType() {
    return returnType;
  }

  /** Returns the list of parameter types. */
  public List<String> getParamTypes() {
    return paramTypes;
  }
}
