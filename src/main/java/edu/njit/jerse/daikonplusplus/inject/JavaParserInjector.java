package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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

          // Group by method descriptor + kind (store both resolved and simple-name variants)
          Map<String, List<InvariantRecord>> entryByM = new HashMap<>();
          Map<String, List<InvariantRecord>> exitByM = new HashMap<>();
          java.util.function.Function<String, String> toSimple =
              d ->
                  d.replaceAll("\\b([A-Za-z_]\\w*)(?:\\.[A-Za-z_]\\w*)+\\b", "$1")
                      .replace("java.lang.", "");

          for (var rec : recordsForThisFile) {
            String key = rec.point().elementId().jvmDescriptor();
            String keySimple = toSimple.apply(key);
            if (rec.point().kind() == ProgramPointKind.METHOD_ENTRY) {
              entryByM.computeIfAbsent(key, __ -> new ArrayList<>()).add(rec);
              if (!keySimple.equals(key)) {
                entryByM.computeIfAbsent(keySimple, __ -> new ArrayList<>()).add(rec);
              }
            } else if (rec.point().kind() == ProgramPointKind.METHOD_EXIT) {
              exitByM.computeIfAbsent(key, __ -> new ArrayList<>()).add(rec);
              if (!keySimple.equals(key)) {
                exitByM.computeIfAbsent(keySimple, __ -> new ArrayList<>()).add(rec);
              }
            }
          }

          cu.findAll(MethodDeclaration.class, md -> md.getBody().isPresent())
              .forEach(
                  md -> {
                    final String desc = MethodSignatureUtil.jvmDescriptorBestEffort(md);
                    final String descSimple = toSimple.apply(desc);
                    final BlockStmt body = md.getBody().orElseThrow();

                    // ---- ENTRY: prepend guards (simple; may duplicate on re-runs)
                    List<InvariantRecord> entries =
                        entryByM.get(desc) != null ? entryByM.get(desc) : entryByM.get(descSimple);

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
                    List<InvariantRecord> exits =
                        exitByM.get(desc) != null ? exitByM.get(desc) : exitByM.get(descSimple);

                    if (exits != null && !exits.isEmpty()) {
                      final boolean isVoid = md.getType().isVoidType();
                      final int[] counter = {0}; // unique temp names per return

                      for (ReturnStmt ret : body.findAll(ReturnStmt.class)) {
                        if (isInForbiddenContext(ret, md)) continue;
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

          Files.writeString(file, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
          return null;
        });
  }

  private static Statement guardStatementWithExVarOneLine(
      InvariantRecord rec, String phase, String exVar) {
    final String expr = rec.spec().expression();
    final String id = rec.id().toString();
    final String tryCode =
        "try {\n"
            + "  if (daikonpp.DpRuntime.ENABLED) {\n"
            + "    String __dp_id = \""
            + id
            + "\";\n"
            + "    if (daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {\n"
            + "      System.out.println(\"INV_EXD:\" + __dp_id);\n"
            + "      if (daikonpp.DpRuntime.HOOK_REGISTERED.compareAndSet(false, true)) {\n"
            + "        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {\n"
            + "          public void run() {\n"
            + "            try {\n"
            + "              String __d = daikonpp.DpRuntime.INV_DIR;\n"
            + "              if (__d == null || __d.trim().length() == 0) return;\n"
            + "              java.io.File __dir = new java.io.File(__d);\n"
            + "              __dir.mkdirs();\n"
            + "              java.io.File __out = new java.io.File(\n"
            + "                __dir,\n"
            + "                \"dp-events-\" + java.util.UUID.randomUUID().toString() + \".log\"\n"
            + "              );\n"
            + "              StringBuilder __sb = new StringBuilder();\n"
            + "              for (String __k : daikonpp.DpRuntime.EXECUTED.keySet()) {\n"
            + "                __sb.append(\"INV_EXD:\").append(__k).append('\\n');\n"
            + "              }\n"
            + "              for (String __v : daikonpp.DpRuntime.FAIL_JSON.values()) {\n"
            + "                if (__v != null && __v.trim().length() > 0)\n"
            + "                  __sb.append(__v).append('\\n');\n"
            + "              }\n"
            + "              if (__sb.length() > 0) {\n"
            + "                java.io.OutputStream __os = null;\n"
            + "                try {\n"
            + "                  __os = new java.io.FileOutputStream(__out, true);\n"
            + "                  __os.write(__sb.toString().getBytes(\"UTF-8\"));\n"
            + "                } finally {\n"
            + "                  if (__os != null) try { __os.close(); } catch (Throwable __t) {}\n"
            + "                }\n"
            + "              }\n"
            + "            } catch (Throwable __ignore) {}\n"
            + "          }\n"
            + "        }));\n"
            + "      }\n"
            + "    }\n"
            + "    boolean __dp_ok = true;\n"
            + "    if (daikonpp.DpRuntime.GUARD.compareAndSet(false, true)) {\n"
            + "      try {\n"
            + "        __dp_ok = ("
            + expr
            + ");\n"
            + "      } catch (Throwable __t) {\n"
            + "        __dp_ok = false;\n"
            + "      } finally {\n"
            + "        daikonpp.DpRuntime.GUARD.set(false);\n"
            + "      }\n"
            + "    }\n"
            + "    if (!__dp_ok) {\n"
            + "      String __json =\n"
            + "        \"{\\\"type\\\":\\\"INV_FAIL\\\",\" +\n"
            + "        \"\\\"id\\\":\\\""
            + id
            + "\\\",\" +\n"
            + "        \"\\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\",\" +\n"
            + "        \"\\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\",\" +\n"
            + "        \"\\\"expr\\\":\\\""
            + esc(expr)
            + "\\\",\" +\n"
            + "        \"\\\"phase\\\":\\\""
            + phase
            + "\\\"}\";\n"
            + "      if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(__dp_id, __json) == null) {\n"
            + "        System.out.println(__json);\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "} catch (Throwable "
            + exVar
            + ") {\n"
            + "  String __json =\n"
            + "    \"{\\\"type\\\":\\\"INV_FAIL\\\",\" +\n"
            + "    \"\\\"id\\\":\\\""
            + id
            + "\\\",\" +\n"
            + "    \"\\\"error\\\":\\\"\" + "
            + exVar
            + ".toString() + \"\\\"}\";\n"
            + "  if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(\""
            + id
            + "\", __json) == null) {\n"
            + "    System.out.println(__json);\n"
            + "  }\n"
            + "}\n";
    return StaticJavaParser.parseStatement(tryCode);
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
   * Returns {@code true} if {@code ret} is nested inside a scope that is not the direct body of
   * {@code owner} — e.g. an anonymous class, lambda, nested method, or constructor that was itself
   * injected into the body. In such cases the return should not be treated as a method exit point.
   */
  private static boolean isInForbiddenContext(ReturnStmt ret, MethodDeclaration owner) {
    Node current = ret.getParentNode().orElse(null);
    while (current != null) {
      if (current.equals(owner)) return false;
      if (current instanceof MethodDeclaration
          || current instanceof ConstructorDeclaration
          || current instanceof LambdaExpr
          || current instanceof ClassOrInterfaceDeclaration
          || current instanceof EnumDeclaration
          || current instanceof AnnotationDeclaration
          || current instanceof ObjectCreationExpr) {
        return true;
      }
      current = current.getParentNode().orElse(null);
    }
    return false;
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
