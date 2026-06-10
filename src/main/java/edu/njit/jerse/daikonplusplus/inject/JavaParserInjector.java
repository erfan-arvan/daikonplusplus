package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
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
 * Injects invariant checks into Java source code using JavaParser.
 *
 * <p>For each method, invariant guards are inserted at method entry and exit.
 */
public final class JavaParserInjector {

  private final FileWriteCoordinator coordinator;

  /**
   * Creates a new injector.
   *
   * @param coordinator file write coordinator
   */
  public JavaParserInjector(FileWriteCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  /**
   * Escapes a string for inclusion in generated code.
   *
   * @param s input string
   * @return escaped string
   */
  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /**
   * Injects invariant checks into a source file.
   *
   * @param file source file to modify
   * @param records invariants to inject
   * @throws Exception if parsing or writing fails
   */
  public void injectGuards(Path file, List<InvariantRecord> records) throws Exception {
    if (records == null || records.isEmpty()) {
      return;
    }

    coordinator.withFileLock(
        file,
        () -> {
          String src = Files.readString(file, StandardCharsets.UTF_8);
          CompilationUnit cu = LexicalPreservingPrinter.setup(StaticJavaParser.parse(src));

          Map<String, List<InvariantRecord>> entryMap = new HashMap<>();
          Map<String, List<InvariantRecord>> exitMap = new HashMap<>();

          // Group invariants by descriptor / simple descriptor
          for (InvariantRecord r : records) {
            String key = r.point().elementId().jvmDescriptor();
            Map<String, List<InvariantRecord>> target =
                (r.point().kind() == ProgramPointKind.METHOD_ENTRY) ? entryMap : exitMap;

            target.computeIfAbsent(key, __ -> new ArrayList<>()).add(r);
          }

          // For each method, attach entry/exit invariants if present
          for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (!md.getBody().isPresent()) {
              continue;
            }

            String desc = MethodSignatureUtil.jvmDescriptorBestEffort(md);

            List<InvariantRecord> entries = entryMap.get(desc);
            List<InvariantRecord> exits = exitMap.get(desc);

            if (entries != null && !entries.isEmpty()) {
              injectEntry(md, entries);
            }
            if (exits != null && !exits.isEmpty()) {
              injectExit(md, exits);
            }
          }

          Files.writeString(file, LexicalPreservingPrinter.print(cu), StandardCharsets.UTF_8);
          return null;
        });
  }

  /**
   * Inserts invariant checks at method entry.
   *
   * @param md method declaration
   * @param entries invariants for entry
   */
  private void injectEntry(MethodDeclaration md, List<InvariantRecord> entries) {
    BlockStmt body = md.getBody().get();
    List<Statement> stmts = body.getStatements();

    int idx = 0;
    for (InvariantRecord rec : entries) {
      String exVar = "__dp_ex_" + rec.id().toString().replace("-", "") + "_en";
      stmts.add(idx++, guardStatement(rec, "ENTRY", exVar));
    }
  }

  /**
   * Inserts invariant checks at method exit.
   *
   * @param md method declaration
   * @param exits invariants for exit
   */
  private void injectExit(MethodDeclaration md, List<InvariantRecord> exits) {
    BlockStmt body = md.getBody().get();
    boolean isVoid = md.getType().isVoidType();

    List<ReturnStmt> returns = body.findAll(ReturnStmt.class);
    int[] counter = {0};

    for (ReturnStmt ret : returns) {
      if (isInForbiddenContext(ret, md)) {
        continue;
      }

      if (ret.getExpression().isPresent()) {
        ret.replace(exitReturnBlock(md, ret, exits, counter));
      } else {
        ret.replace(exitVoidBlock(exits));
      }
    }

    // Tail guards for void methods that fall through
    if (isVoid) {
      List<Statement> stmts = body.getStatements();
      if (stmts.isEmpty() || !(stmts.get(stmts.size() - 1) instanceof ReturnStmt)) {
        for (InvariantRecord rec : exits) {
          String exVar = "__dp_ex_" + rec.id().toString().replace("-", "") + "_tail";
          stmts.add(guardStatement(rec, "EXIT", exVar));
        }
      }
    }
  }

  /**
   * Rewrites a return statement to include invariant checks before returning.
   *
   * @param md method declaration
   * @param ret original return statement
   * @param exits invariants for exit
   * @param counter counter for temporary variables
   * @return replacement statement
   */
  private Statement exitReturnBlock(
      MethodDeclaration md, ReturnStmt ret, List<InvariantRecord> exits, int[] counter) {

    Expression rhs = ret.getExpression().get();

    if (rhs.isLambdaExpr() || rhs.isMethodReferenceExpr()) {
      return ret.clone();
    }

    String tmp = "__dp_res" + (++counter[0]);

    BlockStmt block = new BlockStmt();
    block.addStatement(hoistTemp(md, tmp, rhs));

    int g = 0;
    for (InvariantRecord rec : exits) {
      InvariantRecord rewritten = rewriteResult(rec, tmp);
      String exVar = "__dp_ex_" + rec.id().toString().replace("-", "") + "_ex" + g++;
      block.addStatement(guardStatement(rewritten, "EXIT", exVar));
    }

    // Return with correct type
    block.addStatement(new ReturnStmt(new NameExpr(tmp)));

    return block;
  }

  /**
   * Creates a block for void returns with invariant checks.
   *
   * @param exits invariants for exit
   * @return block statement
   */
  private Statement exitVoidBlock(List<InvariantRecord> exits) {
    BlockStmt block = new BlockStmt();
    int g = 0;
    for (InvariantRecord rec : exits) {
      String exVar = "__dp_ex_" + rec.id().toString().replace("-", "") + "_exV_" + g++;
      block.addStatement(guardStatement(rec, "EXIT", exVar));
    }
    block.addStatement(new ReturnStmt());
    return block;
  }

  /**
   * Creates a temporary variable assignment for a return expression.
   *
   * @param md method declaration
   * @param tmp temporary variable name
   * @param rhs original return expression
   * @return statement assigning the expression to the temporary variable
   */
  private Statement hoistTemp(MethodDeclaration md, String tmp, Expression rhs) {
    String type = md.getType().toString();

    if (rhs.isNullLiteralExpr()) {
      return StaticJavaParser.parseStatement("final " + type + " " + tmp + " = null;");
    }

    return StaticJavaParser.parseStatement("final " + type + " " + tmp + " = " + rhs + ";");
  }

  /**
   * Returns the boxed type name for a primitive type.
   *
   * @param pt primitive type
   * @return boxed type name
   */
  private static String boxedType(PrimitiveType pt) {
    return switch (pt.getType()) {
      case BOOLEAN -> "Boolean";
      case BYTE -> "Byte";
      case SHORT -> "Short";
      case INT -> "Integer";
      case LONG -> "Long";
      case CHAR -> "Character";
      case FLOAT -> "Float";
      case DOUBLE -> "Double";
    };
  }

  /**
   * Builds a guarded invariant check statement.
   *
   * @param rec invariant record
   * @param phase execution phase ("ENTRY" or "EXIT")
   * @param exVar exception variable name
   * @return statement implementing the guard
   */
  private Statement guardStatement(InvariantRecord rec, String phase, String exVar) {
    String id = rec.id().toString();
    String expr = rec.spec().expression();

    // Build the try/catch using DpRuntime (no System.getProperties() — avoids JVM-wide lock)
    String tryCode =
        "try {\n"
            + "  if (daikonpp.DpRuntime.ENABLED) {\n"
            + "    String __dp_id = \""
            + id
            + "\";\n"
            + "    if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {\n"
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
            + "    if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {\n"
            + "      try {\n"
            + "        __dp_ok = ("
            + expr
            + ");\n"
            + "      } catch (Throwable __t) {\n"
            + "        __dp_ok = false;\n"
            + "      } finally {\n"
            + "        daikonpp.DpRuntime.GUARD.get().set(false);\n"
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
            + "    System.out.println(\"INV_DON:\" + __dp_id);\n"
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

    Statement tryStmt = StaticJavaParser.parseStatement(tryCode);

    // BEGIN marker: an empty statement with a line comment
    EmptyStmt begin = new EmptyStmt();
    begin.setComment(new com.github.javaparser.ast.comments.LineComment("__DP_INVARIANT_BEGIN__"));

    // END marker: another empty statement with a line comment
    EmptyStmt end = new EmptyStmt();
    end.setComment(new com.github.javaparser.ast.comments.LineComment("__DP_INVARIANT_END__"));

    // Wrap: { /*begin*/ ; try { ... } ; /*end*/ ; }
    BlockStmt block = new BlockStmt();
    block.addStatement(begin);
    block.addStatement(tryStmt);
    block.addStatement(end);

    return block;
  }

  /**
   * Rewrites occurrences of {@code result} in an invariant expression.
   *
   * @param rec original invariant record
   * @param tmpVar replacement variable
   * @return updated invariant record
   */
  private InvariantRecord rewriteResult(InvariantRecord rec, String tmpVar) {
    String newExpr = rec.spec().expression().replaceAll("\\bresult\\b", tmpVar);
    return new InvariantRecord(
        rec.id(),
        new InvariantSpec(newExpr, rec.spec().rationale(), rec.spec().meta()),
        rec.point(),
        rec.sourceFile(),
        rec.createdAt());
  }

  /**
   * Checks whether a return statement is in a context where rewriting is unsafe.
   *
   * @param ret return statement
   * @param owner enclosing method
   * @return true if rewriting should be skipped
   */
  @SuppressWarnings("interned")
  private boolean isInForbiddenContext(ReturnStmt ret, MethodDeclaration owner) {
    Node n = ret;
    while (n.getParentNode().isPresent()) {
      n = n.getParentNode().get();

      if (n == owner) {
        return false;
      }

      if (n instanceof MethodDeclaration) return true;
      if (n instanceof ConstructorDeclaration) return true;
      if (n instanceof LambdaExpr) return true;
      if (n instanceof ClassOrInterfaceDeclaration) return true;
      if (n instanceof EnumDeclaration) return true;
      if (n instanceof AnnotationDeclaration) return true;
      if (n instanceof ObjectCreationExpr
          && ((ObjectCreationExpr) n).getAnonymousClassBody().isPresent()) {
        return true;
      }
      if (n instanceof SwitchExpr) return true;
    }
    return true;
  }
}
