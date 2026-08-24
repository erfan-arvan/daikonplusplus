package edu.njit.jerse.daikonplusplus.results;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Collects the names of methods declared by the project under analysis, so invariant expressions
 * can be checked for calls that target project code rather than the Java standard library.
 */
public final class ProjectMethodIndex {

  /**
   * Common {@code java.lang.Object} overrides excluded from the index, since a call to one of these
   * names is not a meaningful signal of project-specific behavior even when the project happens to
   * declare it.
   */
  private static final Set<String> OBJECT_METHOD_NAMES =
      Set.of("equals", "hashCode", "toString", "clone", "finalize", "wait", "notify", "notifyAll");

  private ProjectMethodIndex() {}

  /**
   * Walks {@code srcRoot} and returns the set of method names declared anywhere in its {@code
   * .java} files, excluding common {@code Object} overrides. Files that fail to parse are skipped.
   *
   * @param srcRoot root of the project's Java source tree
   * @return set of project-declared method names
   */
  public static Set<String> collect(Path srcRoot) {
    Set<String> names = new HashSet<>();
    if (srcRoot == null || !Files.isDirectory(srcRoot)) return names;

    try (var walk = Files.walk(srcRoot)) {
      walk.filter(p -> p.toString().endsWith(".java"))
          .forEach(
              p -> {
                try {
                  StaticJavaParser.parse(p.toFile())
                      .findAll(MethodDeclaration.class)
                      .forEach(m -> names.add(m.getNameAsString()));
                } catch (IOException | RuntimeException ignore) {
                  // best-effort: skip files that don't parse
                }
              });
    } catch (IOException e) {
      throw new RuntimeException("Failed to walk source tree: " + e.getMessage(), e);
    }

    names.removeAll(OBJECT_METHOD_NAMES);
    return names;
  }
}
