package edu.njit.jerse.daikonplusplus.parse;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.stream.Collectors;

/**
 * Utility class for constructing method descriptors used to uniquely identify methods.
 *
 * <p>Descriptors include the method name, parameter types, and return type to avoid collisions
 * between overloaded methods.
 */
public final class MethodSignatureUtil {
  private MethodSignatureUtil() {}

  /**
   * Builds a descriptor for a resolved method using fully qualified type names.
   *
   * <p>Format: {@code methodName(paramType1,paramType2):returnType}
   *
   * @param rmd resolved method declaration
   * @return descriptor string including fully qualified parameter and return types
   */
  public static String jvmDescriptor(ResolvedMethodDeclaration rmd) {
    String params =
        rmd.getNumberOfParams() == 0
            ? ""
            : java.util.stream.IntStream.range(0, rmd.getNumberOfParams())
                .mapToObj(i -> rmd.getParam(i).getType().describe())
                .collect(Collectors.joining(","));
    String ret = rmd.getReturnType().describe();
    return rmd.getName() + "(" + params + "):" + ret;
  }

  /**
   * Builds a descriptor for a method without requiring symbol resolution.
   *
   * <p>Uses syntactic type names from the source code, which may not be fully qualified.
   *
   * @param md method declaration
   * @return descriptor string using best-effort type names
   */
  public static String jvmDescriptorBestEffort(MethodDeclaration md) {
    String params =
        md.getParameters().stream()
            .map(p -> p.getType().toString())
            .collect(Collectors.joining(","));
    String ret = md.getType().toString();
    return md.getNameAsString() + "(" + params + "):" + ret;
  }
}
