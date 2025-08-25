package edu.njit.jerse.daikonplusplus.llm;

import com.github.javaparser.StaticJavaParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.*;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class InvariantQualityFilter {

  private InvariantQualityFilter() {}

  // ---------- trivial / always-true patterns ----------
  private static final Pattern TAUTOLOGY_SIMPLE =
      Pattern.compile("^\\s*!\\s*(.+)\\s*\\|\\|\\s*\\1\\s*$"); // "!X || X"
  private static final Set<String> ALWAYS_TRUE_LITERALS = Set.of("true", "(true)");
  private static final Pattern ALWAYS_TRUE_RANGES =
      Pattern.compile("\\b(?:Integer\\.(?:MAX|MIN)_VALUE|Long\\.(?:MAX|MIN)_VALUE)\\b");
  private static final Pattern NONNEG_LENGTH =
      Pattern.compile("\\b(length|size)\\s*\\(\\)\\s*>=\\s*0\\b");
  private static final Pattern BOOL_EQ =
      Pattern.compile("\\b==\\s*(true|false)\\b|\\b!=\\s*(true|false)\\b");

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
        "catch ("
      };

  // allow known safe JDK utility qualifiers
  private static final Set<String> ALLOWED_PREFIXES =
      Set.of("Math.", "Integer.", "Long.", "Double.", "Short.", "Byte.", "Character.", "Objects.");

  /** Backward‑compatible entry point (no symbol check, as before). */
  public static boolean keep(
      @NonNull String exprString, Map<String, String> inScope, boolean isExit) {
    return keep(exprString, inScope, isExit, /*classpathEntries*/ Collections.emptyList());
  }

  /**
   * New entry point: adds collection‑API sanity + stub‑compile symbol check with the given
   * classpath.
   *
   * @param classpathEntries paths to jars/dirs (program JAR, libs, classes/, etc.)
   */
  public static boolean keep(
      @NonNull String exprString,
      Map<String, String> inScope,
      boolean isExit,
      List<String> classpathEntries) {

    String e = exprString.trim();
    if (e.isEmpty()) return false;

    // 0) keep expressions reasonably compact
    if (e.length() > 200) return false;

    // 1) trivialities / tautologies
    if (ALWAYS_TRUE_LITERALS.contains(e)) return false;
    if (TAUTOLOGY_SIMPLE.matcher(e).matches()) return false;
    if (ALWAYS_TRUE_RANGES.matcher(e).find()) return false;
    if (NONNEG_LENGTH.matcher(e).find()) return false;
    if (BOOL_EQ.matcher(e).find()) return false;

    // 2) structural constraints
    if (ILLEGAL_CHARS.matcher(e).find()) return false;
    if (BARE_ASSIGN.matcher(e).find()) return false;

    // 3) no heavy/fragile constructs that add imports or helpers
    for (String bad : FORBIDDEN_SUBSTRINGS) {
      if (e.contains(bad)) return false;
    }

    // 4) must reference at least one in-scope name
    if (!mentionsAny(e, new ArrayList<>(inScope.keySet()))) return false;

    // 5) EXIT constraints
    if (isExit && inScope.containsKey("result") && !containsWord(e, "result")) return false;

    // 6) reject null-comparisons on primitives
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

    // 6.5) Collection‑API sanity using receiver types we know from inScope
    // e.g., "xs.containsKey(k)" but xs is List -> reject; "xs.length" but xs is List -> reject,
    // etc.
    if (!collectionApiSanity(e, inScope)) return false;

    // 7) light unknown‑identifier screen
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
      if (w == null) continue;
      if (known.contains(w)) continue;
      if (Character.isDigit(w.charAt(0))) continue;
      int pos = m.start();
      if (pos > 0 && e.charAt(pos - 1) == '.') continue;
      if (allowLib && isAllowedLibQualifier(w)) continue;
      return false;
    }

    // 8) parse expression syntax
    try {
      StaticJavaParser.parseExpression(e);
    } catch (Exception parseErr) {
      return false;
    }

    // 9) **Symbol check via stub compile** (semantic gate)
    // Only run when we have a classpath from the caller.
    if (classpathEntries != null && !classpathEntries.isEmpty()) {
      if (!symbolCheckWithJavac(e, inScope, classpathEntries)) {
        return false;
      }
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
    return t.equals("boolean")
        || t.equals("byte")
        || t.equals("short")
        || t.equals("int")
        || t.equals("long")
        || t.equals("char")
        || t.equals("float")
        || t.equals("double");
  }

  private static boolean isAllowedLibQualifier(String w) {
    return w.equals("Math")
        || w.equals("Integer")
        || w.equals("Objects")
        || w.equals("Long")
        || w.equals("Double")
        || w.equals("Short")
        || w.equals("Byte")
        || w.equals("Character");
  }

  /**
   * Very cheap receiver‑type checks for common API mismatches using declared types from inScope. We
   * only look at direct patterns like "x.containsKey(...)" and "x.length".
   */
  private static boolean collectionApiSanity(String e, Map<String, String> inScope) {
    // receiver.method(...) pattern
    final Pattern call =
        Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    final Matcher mc = call.matcher(e);
    while (mc.find()) {
      final String recv = mc.group(1);
      final String meth = mc.group(2);
      // Null/empty guards for CF
      if (recv == null || recv.isEmpty() || meth == null || meth.isEmpty()) continue;

      final String ty = inScope.get(recv); // recv is non-null here
      if (ty == null) continue;

      final boolean isArr = ty.endsWith("[]");
      final boolean isList = ty.contains("List<") || ty.startsWith("java.util.List") || isArr;
      final boolean isMap = ty.contains("Map<") || ty.startsWith("java.util.Map");

      if (isList
          && (meth.equals("containsKey")
              || meth.equals("keySet")
              || meth.equals("entrySet")
              || meth.equals("put"))) {
        return false; // map-only API on a list/array
      }
      if (isMap && (meth.equals("contains") || meth.equals("indexOf"))) {
        return false; // list-only API on a map
      }
      if (isArr && (meth.equals("size") || meth.equals("isEmpty"))) {
        return false; // arrays use ".length"
      }
    }

    // receiver.length on non-array
    final Pattern lengthDot = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*length\\b");
    final Matcher ml = lengthDot.matcher(e);
    while (ml.find()) {
      final String recv = ml.group(1);
      if (recv == null || recv.isEmpty()) continue;
      final String ty = inScope.get(recv);
      if (ty == null) continue;
      final boolean isArr = ty.endsWith("[]");
      if (!isArr) return false; // only arrays have .length (String has length())
    }
    return true;
  }

  /**
   * Semantic gate: compile a tiny stub using the same classpath the project will use. If it doesn't
   * compile, we reject the expression.
   */
  private static boolean symbolCheckWithJavac(
      String expr, Map<String, String> inScope, List<String> classpathEntries) {
    try {
      JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
      if (jc == null) {
        // Running on a JRE without tools.jar; conservatively accept
        return true;
      }
      StringBuilder decls = new StringBuilder();
      for (var e : inScope.entrySet()) {
        String v = e.getKey();
        String t = e.getValue();
        if (!isValidIdentifier(v)) continue;
        decls.append(t).append(" ").append(v).append(" = ").append(defaultInitFor(t)).append(";\n");
      }
      String src =
          "public class __DP_Stub {\n"
              + "  public static boolean test() {\n"
              + "    "
              + decls
              + "    return ("
              + expr
              + ");\n"
              + "  }\n"
              + "}\n";

      Path tmpDir = Files.createTempDirectory("__dp_stub_");
      Path file = tmpDir.resolve("__DP_Stub.java");
      Files.writeString(file, src);

      List<String> opts = new ArrayList<>();
      if (!classpathEntries.isEmpty()) {
        String cp = String.join(File.pathSeparator, classpathEntries);
        opts.add("-cp");
        opts.add(cp);
      }

      StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(file.toFile());
      JavaCompiler.CompilationTask task = jc.getTask(null, fm, null, opts, null, units);
      boolean ok = task.call();
      fm.close();

      // cleanup
      try {
        Files.deleteIfExists(file);
        Files.deleteIfExists(tmpDir);
      } catch (Exception ignore) {
      }

      return ok;
    } catch (Exception ex) {
      return false;
    }
  }

  private static boolean isValidIdentifier(String s) {
    if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
    for (int i = 1; i < s.length(); i++)
      if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
    return true;
  }

  private static String defaultInitFor(String t) {
    t = t.trim();
    return switch (t) {
      case "boolean" -> "false";
      case "byte", "short", "int", "long", "char", "float", "double" -> "0";
      default -> "null";
    };
  }
}
