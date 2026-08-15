package edu.njit.jerse.daikonplusplus.model;

import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Identifies a program element such as a method.
 *
 * <p>The identifier includes package name, class hierarchy, source file, and a descriptor to
 * distinguish overloaded elements.
 */
public final class ProgramElementId {
  private final String packageName;
  private final String topLevelClass;
  private final String nestedClassPath; // e.g., "Inner$More" or ""
  private final String filePath; // project-relative or absolute
  private final String jvmDescriptor; // for methods: "m(int,java.lang.String):void"

  private ProgramElementId(
      String packageName,
      String topLevelClass,
      String nestedClassPath,
      String filePath,
      String jvmDescriptor) {
    this.packageName = packageName;
    this.topLevelClass = topLevelClass;
    this.nestedClassPath = nestedClassPath;
    this.filePath = filePath;
    this.jvmDescriptor = jvmDescriptor;
  }

  /**
   * Creates an identifier for a method.
   *
   * @param packageName package name (may be empty)
   * @param topLevelClass top-level class name
   * @param nestedClassPath nested class path (may be empty)
   * @param filePath source file path
   * @param jvmDescriptor method descriptor
   * @return program element identifier
   */
  public static ProgramElementId forMethod(
      String packageName,
      String topLevelClass,
      String nestedClassPath,
      String filePath,
      String jvmDescriptor) {
    return new ProgramElementId(
        packageName == null ? "" : packageName,
        topLevelClass,
        nestedClassPath == null ? "" : nestedClassPath,
        filePath,
        jvmDescriptor);
  }

  public String packageName() {
    return packageName;
  }

  public String topLevelClass() {
    return topLevelClass;
  }

  public String nestedClassPath() {
    return nestedClassPath;
  }

  public String filePath() {
    return filePath;
  }

  public String jvmDescriptor() {
    return jvmDescriptor;
  }

  /** human-readable label like: a.b.C$Inner#m(int):void */
  @Override
  public String toString() {
    String cls = nestedClassPath.isEmpty() ? topLevelClass : topLevelClass + "$" + nestedClassPath;
    String pkg = packageName.isEmpty() ? "" : packageName + ".";
    return pkg + cls + "#" + jvmDescriptor;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) return true;
    if (!(o instanceof ProgramElementId)) return false;
    ProgramElementId that = (ProgramElementId) o;
    return java.util.Objects.equals(packageName, that.packageName)
        && java.util.Objects.equals(topLevelClass, that.topLevelClass)
        && java.util.Objects.equals(nestedClassPath, that.nestedClassPath)
        && java.util.Objects.equals(filePath, that.filePath)
        && java.util.Objects.equals(jvmDescriptor, that.jvmDescriptor);
  }

  /**
   * Computes the hash code.
   *
   * @return hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(packageName, topLevelClass, nestedClassPath, filePath, jvmDescriptor);
  }
}
