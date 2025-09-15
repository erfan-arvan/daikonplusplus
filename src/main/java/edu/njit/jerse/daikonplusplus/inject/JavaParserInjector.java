package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import edu.njit.jerse.daikonplusplus.model.InvariantRecord;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs source-to-source injection of invariant guards using JavaParser.
 *
 * <p>This utility parses a Java source file, locates method bodies, and injects try/catch-wrapped
 * invariant checks at method entry and exit points. All writes are coordinated via {@link
 * FileWriteCoordinator} to avoid concurrent edits.
 *
 * <p><strong>Logging:</strong> When a check fails or throws, a single-line JSON record is printed
 * to {@link System#out}.
 */
public final class JavaParserInjector {

  private final FileWriteCoordinator coordinator;

  /**
   * Creates an injector that serializes writes through the given coordinator.
   *
   * @param coordinator file-write coordinator used to serialize updates
   */
  public JavaParserInjector(FileWriteCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  /**
   * Escapes a string for safe embedding inside a Java string literal that itself will be emitted
   * into source and used inside a JSON string field.
   *
   * <p>This replaces backslashes and double quotes with escaped forms.
   *
   * @param s input string
   * @return escaped string
   */
  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Injects both <em>entry</em> and <em>exit</em> invariants for the given source file.
   *
   * <p><strong>Entry:</strong> For each method, emits guards at the beginning of the body. <br>
   * <strong>Exit:</strong> For each {@code return}:
   *
   * <ul>
   *   <li>If the return has an expression, the expression is hoisted to a fresh temporary
   *       (idempotently), exit guards are evaluated using that temporary (with occurrences of
   *       {@code result} rewritten), then the temporary is returned.
   *   <li>If the return is {@code void}, guards are placed before the {@code return;}.
   *   <li>For {@code void} methods with fall-through, guards are appended in tail position.
   * </ul>
   *
   * <p>Multiple guards are supported; each catch variable name is made unique.
   *
   * @param file the Java source file to update (on disk)
   * @param recordsForThisFile invariants whose {@code sourceFile()} equals {@code file}; both
   *     {@code METHOD_ENTRY} and {@code METHOD_EXIT} are considered
   * @throws Exception if parsing, transformation, or writing the file fails
   */
  public void injectGuards(Path file, List<InvariantRecord> recordsForThisFile) throws Exception {
    if (recordsForThisFile == null || recordsForThisFile.isEmpty()) return;

    coordinator.withFileLock(
        file,
        () -> {
          CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(file));

          // Group by method descriptor + kind
          Map<String, List<InvariantRecord>> entryByM = new HashMap<>();
          Map<String, List<InvariantRecord>> exitByM = new HashMap<>();
          for (var rec : recordsForThisFile) {
            String key = rec.point().elementId().jvmDescriptor();
            if (rec.point().kind() == ProgramPointKind.METHOD_ENTRY) {
              entryByM.computeIfAbsent(key, __ -> new ArrayList<>()).add(rec);
            } else if (rec.point().kind() == ProgramPointKind.METHOD_EXIT) {
              exitByM.computeIfAbsent(key, __ -> new ArrayList<>()).add(rec);
            }
          }

          cu.findAll(MethodDeclaration.class, md -> md.getBody().isPresent())
              .forEach(
                  md -> {
                    final String desc = MethodSignatureUtil.jvmDescriptorBestEffort(md);
                    final BlockStmt body = md.getBody().orElseThrow();

                    // ---- ENTRY: prepend guards (simple; may duplicate on re-runs)
                    List<InvariantRecord> entries = entryByM.get(desc);
                    if (entries != null && !entries.isEmpty()) {
                      NodeList<Statement> guards = new NodeList<>();
                      int idx = 0;
                      for (var rec : entries) {
                        String exVar =
                            "__dp_ex_" + rec.id().toString().replace("-", "") + "_en" + (idx++);
                        guards.add(guardStatementWithExVarOneLine(rec, "ENTRY", exVar));
                      }
                      NodeList<Statement> newStmts = new NodeList<>();
                      newStmts.addAll(guards);
                      newStmts.addAll(body.getStatements());
                      body.setStatements(newStmts);
                    }

                    // ---- EXIT: before every return (idempotent) + tail for void fallthrough
                    List<InvariantRecord> exits = exitByM.get(desc);
                    if (exits != null && !exits.isEmpty()) {
                      final boolean isVoid = md.getType().isVoidType();
                      final int[] counter = {0}; // unique temp names per return

                      for (ReturnStmt ret : body.findAll(ReturnStmt.class)) {
                        Optional<Expression> oe = ret.getExpression();

                        if (oe.isPresent()) {
                          String rhs = oe.get().toString().trim();

                          // If return already uses our temp, do NOT redeclare (idempotent)
                          boolean alreadyHoisted =
                              rhs.matches("__dp_res\\d+") || rhs.equals("__dp_result");
                          String tmp = alreadyHoisted ? rhs : "__dp_res" + (++counter[0]);

                          NodeList<Statement> block = new NodeList<>();
                          if (!alreadyHoisted) {
                            block.add(makeHoistedTemp(md, tmp, oe.get()));
                          }

                          // Add EXIT guards referencing tmp (rewrite 'result' -> tmp)
                          int g = 0;
                          for (var rec : exits) {
                            String exVar =
                                "__dp_ex_"
                                    + rec.id().toString().replace("-", "")
                                    + "_ex"
                                    + counter[0]
                                    + "_"
                                    + (g++);
                            block.add(
                                guardStatementWithExVarOneLine(
                                    rewriteResult(rec, tmp), "EXIT", exVar));
                          }

                          // Return the temp
                          block.add(StaticJavaParser.parseStatement("return " + tmp + ";"));
                          ret.replace(new BlockStmt(block));

                        } else {
                          // void return; wrap once (idempotent enough because we don't redeclare
                          // temps)
                          NodeList<Statement> block = new NodeList<>();
                          int g = 0;
                          for (var rec : exits) {
                            String exVar =
                                "__dp_ex_" + rec.id().toString().replace("-", "") + "_exV_" + (g++);
                            block.add(guardStatementWithExVarOneLine(rec, "EXIT", exVar));
                          }
                          block.add(StaticJavaParser.parseStatement("return;"));
                          ret.replace(new BlockStmt(block));
                        }
                      }

                      // Tail position for void methods (fall-through without explicit return)
                      if (isVoid && body.getStatements().isNonEmpty()) {
                        boolean endsWithReturn =
                            body.getStatements()
                                .getLast()
                                .map(s -> s instanceof ReturnStmt)
                                .orElse(false);
                        if (!endsWithReturn) {
                          int g = 0;
                          for (var rec : exits) {
                            String exVar =
                                "__dp_ex_"
                                    + rec.id().toString().replace("-", "")
                                    + "_tail_"
                                    + (g++);
                            body.addStatement(guardStatementWithExVarOneLine(rec, "EXIT", exVar));
                          }
                        }
                      }
                    }
                  });

          String printed = LexicalPreservingPrinter.print(cu);
          Files.writeString(file, collapseOnelineRegions(printed), StandardCharsets.UTF_8);
          return null;
        });
  }

  // ENTRY/EXIT one-liner (caller supplies unique exVar), wrapped in markers
  private static Statement guardStatementWithExVarOneLine(
      InvariantRecord rec, String phase, String exVar) {
    final String expr = rec.spec().expression();
    final String id = rec.id().toString();
    final String code =
        "/*__DP_ONELINE_BEGIN__*/"
            + "try{System.out.println(\\\"INV_EXD:\" + id + \"\\\");if(!("
            + expr
            + ")){System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "\\\"id\\\":\\\""
            + id
            + "\\\",\\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\",\\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\",\\\"expr\\\":\\\""
            + esc(expr)
            + "\\\",\\\"phase\\\":\\\""
            + phase
            + "\\\",\\\"error\\\":\\\"\\\"}\");}}"
            + "catch(Throwable "
            + exVar
            + "){System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "\\\"id\\\":\\\""
            + id
            + "\\\",\\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\",\\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\",\\\"expr\\\":\\\""
            + esc(expr)
            + "\\\",\\\"phase\\\":\\\""
            + phase
            + "\\\",\\\"error\\\":\\\"\"+"
            + exVar
            + ".toString().replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\")+\"\\\"}\");}"
            + "/*__DP_ONELINE_END__*/";
    return StaticJavaParser.parseStatement(code);
  }

  // Collapse marked regions + pull a following '}' up onto the same line.
  @SuppressWarnings({"nullness", "regexp"})
  private static String collapseOnelineRegions(String src) {
    String s = src;

    // Pass 1: collapse our explicit marker regions.
    {
      Pattern p =
          Pattern.compile(
              "/\\*__DP_ONELINE_BEGIN__\\*/\\s*(.*?)\\s*/\\*__DP_ONELINE_END__\\*/",
              Pattern.DOTALL);
      Matcher m = p.matcher(s);
      StringBuffer buf = new StringBuffer();
      while (m.find()) {
        String inner = m.group(1);
        if (inner == null) inner = "";
        inner = inner.replaceAll("\\s+", " ").trim();
        m.appendReplacement(buf, Matcher.quoteReplacement(inner));
      }
      m.appendTail(buf);
      s = buf.toString();
    }

    // Pass 2: collapse any injected try/catch using our __dp_ex_ pattern (defensive).
    {
      Pattern p =
          Pattern.compile(
              "(?s)try\\s*\\{\\s*.*?\\}\\s*catch\\s*\\(\\s*Throwable\\s+__dp_ex_[A-Za-z0-9_]+\\s*\\)\\s*\\{\\s*.*?\\}");
      Matcher m = p.matcher(s);
      StringBuffer buf = new StringBuffer();
      while (m.find()) {
        String seg = m.group();
        String collapsed = seg.replaceAll("\\s+", " ").trim();
        m.appendReplacement(buf, Matcher.quoteReplacement(collapsed));
      }
      m.appendTail(buf);
      s = buf.toString();
    }

    // Pass 3 (SAFE): comment-aware brace hoist (optional; remove entirely if not needed).
    {
      String[] lines = s.split("\\R", -1);
      List<String> out = new ArrayList<>(lines.length);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (i + 1 < lines.length) {
          String next = lines[i + 1];
          String trimmedNext = next.trim();
          boolean nextIsSoloBrace = trimmedNext.equals("}");
          boolean lineIsLineComment = line.trim().startsWith("//");
          boolean lineEndsBlockComment = line.trim().endsWith("*/");

          if (!lineIsLineComment && !lineEndsBlockComment && nextIsSoloBrace) {
            String trimmed = line.trim();
            if (trimmed.endsWith(";") || trimmed.endsWith("}")) {
              out.add(line + " }");
              i++; // consume the brace line
              continue;
            }
          }
        }
        out.add(line);
      }
      s = String.join(System.lineSeparator(), out);
    }

    return s;
  }

  /**
   * Rewrites occurrences of the Daikon-style placeholder {@code result} in the invariant expression
   * to refer to a supplied temporary variable name.
   *
   * <p>Used during EXIT instrumentation when hoisting return expressions.
   *
   * @param rec the original invariant record
   * @param tmpVar the temporary variable name that stands for the return value
   * @return a new {@link InvariantRecord} with the expression rewritten
   */
  private InvariantRecord rewriteResult(InvariantRecord rec, String tmpVar) {
    String expr = rec.spec().expression().replaceAll("\\bresult\\b", tmpVar);
    InvariantSpec spec = new InvariantSpec(expr, rec.spec().rationale(), rec.spec().meta());
    return new InvariantRecord(rec.id(), spec, rec.point(), rec.sourceFile(), rec.createdAt());
  }

  /**
   * Constructs a hoisted temporary variable declaration for a return expression.
   *
   * <p>This is used when instrumenting method exit points: the original return expression is
   * evaluated once and stored in a uniquely named local variable. All exit invariants then
   * reference this temporary.
   *
   * <p>Special care is required when the return expression is the literal {@code null}: Java cannot
   * infer the type of {@code var} from {@code null}. In that case, the method return type is
   * inserted explicitly as a cast, ensuring the generated statement compiles.
   *
   * @param md the method being instrumented (provides the declared return type)
   * @param tmp the unique temporary variable name
   * @param rhs the expression being hoisted (the original return expression)
   * @return a parsed {@link Statement} declaring the temporary variable
   */
  private static Statement makeHoistedTemp(MethodDeclaration md, String tmp, Expression rhs) {
    if (rhs.isNullLiteralExpr()) {
      String retType = md.getType().toString();
      return StaticJavaParser.parseStatement("final var " + tmp + " = (" + retType + ") null;");
    }
    return StaticJavaParser.parseStatement("final var " + tmp + " = (" + rhs + ");");
  }
}
