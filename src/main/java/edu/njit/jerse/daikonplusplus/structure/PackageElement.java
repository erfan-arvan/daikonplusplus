package edu.njit.jerse.daikonplusplus.structure;

/** Represents a package in the program structure hierarchy. */
public class PackageElement extends ProgramStructureElement {

  /**
   * Constructs a new package element.
   *
   * @param packageName The package's qualified name, or an empty string for the default package.
   */
  public PackageElement(String packageName) {
    super(
        packageName == null ? "" : packageName,
        ProgramElementType.PACKAGE,
        null,
        "<package:" + (packageName == null ? "" : packageName) + ">",
        -1,
        -1,
        null);
  }
}
