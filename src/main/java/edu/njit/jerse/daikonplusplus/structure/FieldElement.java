package edu.njit.jerse.daikonplusplus.structure;

/** Represents a field in the program structure hierarchy. */
public class FieldElement extends ProgramStructureElement {

  /** The type of the field. */
  private final String fieldType;

  /**
   * Constructs a new field element.
   *
   * @param name The field name.
   * @param fieldType The fully qualified type of the field.
   * @param parent The class containing this field.
   * @param filePath Path to the file where the field is declared.
   * @param startLine The line where the field declaration begins.
   * @param endLine The line where the field declaration ends.
   */
  public FieldElement(
      String name,
      String fieldType,
      ProgramStructureElement parent,
      String filePath,
      int startLine,
      int endLine) {
    super(name, ProgramElementType.FIELD, parent, filePath, startLine, endLine, null);
    this.fieldType = fieldType;
  }

  /** Returns the type of the field. */
  public String getFieldType() {
    return fieldType;
  }
}
