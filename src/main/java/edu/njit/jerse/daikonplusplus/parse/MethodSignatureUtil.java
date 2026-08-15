package edu.njit.jerse.daikonplusplus.parse;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import java.util.stream.Collectors;

/** Utilities to produce fully-qualified JVM-like method descriptors to avoid collisions. */
public final class MethodSignatureUtil {
  private MethodSignatureUtil() {}

  /**
   * Builds a stable descriptor like: {@code m(int,java.lang.String):void}.
   *
   * <p>Requires symbol resolution for precise FQNs; if unavailable, falls back to simple type
   * names.
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

  /** Best-effort descriptor without full resolution */
  public static String jvmDescriptorBestEffort(MethodDeclaration md) {
    String params =
        md.getParameters().stream()
            .map(p -> p.getType().toString())
            .collect(Collectors.joining(","));
    String ret = md.getType().toString();
    return md.getNameAsString() + "(" + params + "):" + ret;
  }
}
