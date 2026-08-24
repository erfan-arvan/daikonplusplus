package edu.njit.jerse.daikonplusplus.results;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes, for a single invariant expression, the number of variables it references and the
 * project-specific method calls it makes.
 *
 * @param varCount1 number of distinct variable-like identifiers referenced by the expression
 * @param varCount2 total variable-like identifier occurrences in the expression, counting repeats
 * @param pspmCount number of distinct project-specific methods called by the expression
 * @param pspmCalls the distinct project-specific method names called, sorted
 */
public record InvariantMetrics(int varCount1, int varCount2, int pspmCount, Set<String> pspmCalls) {

  private static final InvariantMetrics EMPTY = new InvariantMetrics(0, 0, 0, Set.of());

  /**
   * Parses {@code exprText} as a Java expression and computes its variable counts and
   * project-specific method call count.
   *
   * <p>A variable-like identifier is any simple identifier reference ({@link NameExpr}) whose first
   * letter is lowercase, following ordinary Java naming convention for fields/locals as opposed to
   * types (e.g. {@code Math}, {@code Integer}). {@code varCount1} is the number of distinct such
   * identifiers; {@code varCount2} is the total number of occurrences, including repeated
   * references to the same variable. A project-specific method call is any method invocation whose
   * method name is present in {@code projectMethodNames}, regardless of receiver.
   *
   * @param exprText the invariant expressed as Java source
   * @param projectMethodNames method names declared by the project under analysis
   * @return computed metrics, or all-zero metrics if {@code exprText} fails to parse
   */
  public static InvariantMetrics compute(String exprText, Set<String> projectMethodNames) {
    if (exprText == null || exprText.isBlank()) return EMPTY;

    final Expression expr;
    try {
      expr = StaticJavaParser.parseExpression(exprText);
    } catch (RuntimeException e) {
      return EMPTY;
    }

    Set<String> distinctVariables = new TreeSet<>();
    int totalVariableOccurrences = 0;
    for (NameExpr ne : expr.findAll(NameExpr.class)) {
      String name = ne.getNameAsString();
      if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) {
        distinctVariables.add(name);
        totalVariableOccurrences++;
      }
    }

    Set<String> pspmCalls = new TreeSet<>();
    for (MethodCallExpr call : expr.findAll(MethodCallExpr.class)) {
      String name = call.getNameAsString();
      if (projectMethodNames.contains(name)) {
        pspmCalls.add(name);
      }
    }

    return new InvariantMetrics(
        distinctVariables.size(), totalVariableOccurrences, pspmCalls.size(), pspmCalls);
  }
}
