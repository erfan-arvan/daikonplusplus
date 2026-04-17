package edu.njit.jerse.daikonplusplus.llm;

import com.github.javaparser.StaticJavaParser;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Conservative and deterministic filters for LLM-proposed invariants. Goal: reject expressions that
 * are likely to be useless or break compilation/runtime.
 */
public final class InvariantQualityFilter {

  private InvariantQualityFilter() {}

  // ---------- trivial / always-true patterns ----------
  // Detect self-comparisons like x == x, a <= a
  private static final Pattern SELF_COMPARISON =
      Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b\\s*(==|!=|<=|>=|<|>)\\s*\\1\\b");

  // Large arbitrary constants (e.g., 1000, 9999)
  //  private static final Pattern LARGE_CONSTANT = Pattern.compile("\\b\\d{3,}\\b");
  private static final Pattern TAUTOLOGY_SIMPLE =
      Pattern.compile("^\\s*!\\s*(.+)\\s*\\|\\|\\s*\\1\\s*$"); // "!X || X"
  private static final Set<String> ALWAYS_TRUE_LITERALS = Set.of("true", "(true)");
  private static final Pattern ALWAYS_TRUE_RANGES =
      Pattern.compile(
          ">=\\s*(Integer\\.MIN_VALUE|Long\\.MIN_VALUE)|<=\\s*(Integer\\.MAX_VALUE|Long\\.MAX_VALUE)");
  //  private static final Pattern NONNEG_LENGTH =
  //      Pattern.compile("\\b(length|size)\\s*\\(\\)\\s*>=\\s*0\\b");
  //  private static final Pattern BOOL_EQ =
  //      Pattern.compile("\\b==\\s*(true|false)\\b|\\b!=\\s*(true|false)\\b");

  // ---------- structural / syntax constraints ----------
  private static final Pattern BARE_ASSIGN = Pattern.compile("(?<![!<>=])=(?!=)");
  private static final Pattern ILLEGAL_CHARS = Pattern.compile("[;{}]"); // keep single-expression
  private static final Pattern WORD = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");
  private static final Pattern NULL_CMP =
      Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b\\s*(==|!=)\\s*null\\b");

  // ---------- ban constructs that require imports or helpers ----------
  private static final String[] FORBIDDEN_SUBSTRINGS =
      new String[] {
        "IntStream",
        ".stream(",
        "Stream<",
        "Collectors",
        "Optional",
        "->",
        "::", // lambdas, method refs
        "Arrays.stream",
        "Pattern",
        "Matcher",
        "existsOutOfOrder", // invented helper from LLM
        "System.exit",
        "Runtime.getRuntime",
        "new ",
        " class ",
        "throw ",
        "catch (" // avoid nested try/catch in expr
      };

  // allow known safe JDK utility qualifiers
  private static final Set<String> ALLOWED_PREFIXES =
      Set.of("Math.", "Integer.", "Long.", "Double.", "Short.", "Byte.", "Character.", "Objects.");

  /**
   * Main filter.
   *
   * @param exprString raw expression text
   * @param inScope varName -> declared type (best-effort). If non-void EXIT, caller should include
   *     "result".
   * @param isExit true iff the program point is METHOD_EXIT
   * @return true if the expression should be kept
   */
  public static boolean keep(
      @NonNull String exprString, Map<String, String> inScope, boolean isExit) {
    String e = exprString.trim();
    if (e.isEmpty()) return false;

    // 0) keep expressions reasonably compact
    if (e.length() > 200) return false;

    // 1) trivialities / tautologies
    if (ALWAYS_TRUE_LITERALS.contains(e)) return false;
    if (TAUTOLOGY_SIMPLE.matcher(e).matches()) return false;
    if (ALWAYS_TRUE_RANGES.matcher(e).find()) return false;
    //    if (NONNEG_LENGTH.matcher(e).find()) return false;
    //    if (BOOL_EQ.matcher(e).find()) return false; // "... == true/false" or "!= true/false"
    if (SELF_COMPARISON.matcher(e).find()) return false;
    if (isTrivialDisjunction(e)) return false;

    //    if (LARGE_CONSTANT.matcher(e).find()) return false;

    // 2) structural constraints
    if (ILLEGAL_CHARS.matcher(e).find()) return false; // no blocks or statements
    if (BARE_ASSIGN.matcher(e).find()) return false; // no assignments

    // 3) no heavy/fragile constructs that add imports or helper needs
    for (String bad : FORBIDDEN_SUBSTRINGS) {
      if (e.contains(bad)) return false;
    }

    // 4) must reference at least one in-scope name (wrap keySet for CF
    // compatibility)
    // Require relationships between variables (not single-variable trivialities)
    if (!mentionsAny(e, new ArrayList<>(inScope.keySet()))) return false;

    // Require relationships between variables (not single-variable trivialities)
    //    if (!isLikelyMeaningful(e, inScope)) return false;

    // 5) for EXIT with non-void methods (caller supplies 'result' in scope),
    // require 'result'
    if (isExit && inScope.containsKey("result") && !containsWord(e, "result")) return false;

    // 6) reject null-comparisons on primitives (e.g., "result != null" when
    // result is int/boolean)
    {
      Matcher nm = NULL_CMP.matcher(e);
      while (nm.find()) {
        final String name = nm.group(1);
        if (name == null) continue;
        final String ty = inScope.get(name);
        if (ty != null && isPrimitiveTypeName(ty.trim())) {
          return false;
        }
      }
    }

    // 7) light "unknown identifier" screen
    Set<String> known = new HashSet<>(inScope.keySet());
    known.addAll(
        Set.of(
            "null",
            "true",
            "false",
            "Math",
            "Integer",
            "Long",
            "Double",
            "Short",
            "Byte",
            "Character",
            "Objects"));
    boolean allowLib = ALLOWED_PREFIXES.stream().anyMatch(e::contains);

    Matcher m = WORD.matcher(e);
    while (m.find()) {
      final String w = m.group(1);
      if (w == null) continue; // for Checker Framework
      if (known.contains(w)) continue;
      if (Character.isDigit(w.charAt(0))) continue;
      // allow as part of qualified call/field: ".w"
      int pos = m.start();
      if (pos > 0 && e.charAt(pos - 1) == '.') continue;
      // allow permitted library qualifiers
      if (allowLib
          && (w.equals("Math")
              || w.equals("Integer")
              || w.equals("Objects")
              || w.equals("Long")
              || w.equals("Double")
              || w.equals("Short")
              || w.equals("Byte")
              || w.equals("Character"))) {
        continue;
      }
      // Unknown standalone symbol → reject
      return false;
    }

    // 8) parse to ensure it's syntactically valid Java expression
    try {
      StaticJavaParser.parseExpression(e);
    } catch (Exception parseErr) {
      return false;
    }

    return true;
  }

  // ---------- Helpers ----------

  private static boolean mentionsAny(String expr, Collection<String> names) {
    for (String n : names) {
      if (containsWord(expr, n)) return true;
    }
    return false;
  }

  private static boolean containsWord(String expr, String word) {
    return expr.matches(".*\\b" + Pattern.quote(word) + "\\b.*");
  }

  private static boolean isPrimitiveTypeName(String t) {
    // best-effort for names we see from JavaParser: exact primitive names
    return t.equals("boolean")
        || t.equals("byte")
        || t.equals("short")
        || t.equals("int")
        || t.equals("long")
        || t.equals("char")
        || t.equals("float")
        || t.equals("double");
  }

  //  private static boolean isLikelyMeaningful(String e, Map<String, String> inScope) {
  //    int count = 0;
  //    for (String v : inScope.keySet()) {
  //      if (containsWord(e, v)) count++;
  //    }
  //    return count >= 2; // require at least two variables
  //  }

  private static final Pattern TRIVIAL_DISJUNCTION_PATTERN =
      Pattern.compile(
          "\\b([A-Za-z_][A-Za-z0-9_]*)\\b\\s*(==|!=)\\s*([^|]+)\\|\\|\\s*\\1\\s*(==|!=)\\s*\\3");

  private static boolean isTrivialDisjunction(String e) {
    Matcher m = TRIVIAL_DISJUNCTION_PATTERN.matcher(e);
    if (!m.find()) return false;

    String op1 = m.group(2);
    String op2 = m.group(4);

    return op1 != null && op2 != null && !op1.equals(op2); // == vs !=
  }
}
