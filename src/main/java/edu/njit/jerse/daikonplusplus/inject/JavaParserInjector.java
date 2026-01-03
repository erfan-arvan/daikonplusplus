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

public final class JavaParserInjector {

  private final FileWriteCoordinator coordinator;

  final int __dp_limit = 20;

  public JavaParserInjector(FileWriteCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

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

  // ======================================================================================
  // ENTRY
  // ======================================================================================

  private void injectEntry(MethodDeclaration md, List<InvariantRecord> entries) {
    BlockStmt body = md.getBody().get();
    List<Statement> stmts = body.getStatements();

    int idx = 0;
    for (InvariantRecord rec : entries) {
      String exVar = "__dp_ex_" + rec.id().toString().replace("-", "") + "_en";
      stmts.add(idx++, guardStatement(rec, "ENTRY", exVar));
    }
  }

  // ======================================================================================
  // EXIT
  // ======================================================================================

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

  private Statement hoistTemp(MethodDeclaration md, String tmp, Expression rhs) {
    String type = md.getType().toString();

    if (rhs.isNullLiteralExpr()) {
      return StaticJavaParser.parseStatement("final " + type + " " + tmp + " = null;");
    }

    return StaticJavaParser.parseStatement("final " + type + " " + tmp + " = " + rhs + ";");
  }

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

  // ======================================================================================
  // GUARD BUILDER
  // ======================================================================================

  private Statement guardStatement(InvariantRecord rec, String phase, String exVar) {
    String id = rec.id().toString();
    String expr = rec.spec().expression();

    // Build the try/catch as a Statement (no markers in the code string)
    String tryCode =
        "try {\n"
            // --------- register shutdown hook once per JVM ---------
            + "  final java.util.Properties __dp_props = System.getProperties();\n"
            + "  if (__dp_props.getProperty(\"DP_INV_HOOK\") == null) {\n"
            + "    synchronized (__dp_props) {\n"
            + "      if (__dp_props.getProperty(\"DP_INV_HOOK\") == null) {\n"
            + "        __dp_props.setProperty(\"DP_INV_HOOK\", \"1\");\n"
            + "        java.lang.Runtime.getRuntime().addShutdownHook(new java.lang.Thread(() -> {\n"
            + "          try {\n"
            + "            final java.util.Properties __p = System.getProperties();\n"
            + "            final String __dirStr = __p.getProperty(\"DP_INV_DIR\");\n"
            + "            if (__dirStr == null || __dirStr.isBlank()) return;\n"
            + "            final java.nio.file.Path __dir = java.nio.file.Paths.get(__dirStr);\n"
            + "            java.nio.file.Files.createDirectories(__dir);\n"
            + "            final long __pid = java.lang.ProcessHandle.current().pid();\n"
            + "            final String __name = \"dp-events-\" + __pid + \"-\" + java.util.UUID.randomUUID() + \".log\";\n"
            + "            final java.nio.file.Path __out = __dir.resolve(__name);\n"
            + "            final java.lang.StringBuilder __sb = new java.lang.StringBuilder();\n"
            + "            for (String __k : __p.stringPropertyNames()) {\n"
            + "              if (__k.startsWith(\"DP_INV_EXD_\")) {\n"
            + "                __sb.append(\"INV_EXD:\").append(__k.substring(\"DP_INV_EXD_\".length())).append('\\n');\n"
            + "              } else if (__k.startsWith(\"DP_INV_FAIL_JSON_\")) {\n"
            + "                final String __v = __p.getProperty(__k);\n"
            + "                if (__v != null && !__v.isBlank()) __sb.append(__v).append('\\n');\n"
            + "              }\n"
            + "            }\n"
            + "            if (__sb.length() > 0) {\n"
            + "              java.nio.file.Files.writeString(\n"
            + "                __out,\n"
            + "                __sb.toString(),\n"
            + "                java.nio.charset.StandardCharsets.UTF_8,\n"
            + "                java.nio.file.StandardOpenOption.CREATE,\n"
            + "                java.nio.file.StandardOpenOption.APPEND);\n"
            + "            }\n"
            + "          } catch (Throwable __ignore) {\n"
            + "            // never crash shutdown\n"
            + "          }\n"
            + "        }));\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"

            // --------- per-invariant: mark executed (once) ---------
            + "  final String __dp_id = \""
            + id
            + "\";\n"
            + "  __dp_props.putIfAbsent(\"DP_INV_EXD_\" + __dp_id, \"1\");\n"

            // --------- evaluate invariant safely (with depth guard) ---------
            + "  boolean __dp_ok = true;\n"
            + "  final ThreadLocal<Integer> __dp_depth =\n"
            + "    (ThreadLocal<Integer>) __dp_props.computeIfAbsent(\n"
            + "      \"DP_INV_DEPTH\", __ -> ThreadLocal.withInitial(() -> 0));\n"
            + "  int __dp_d = __dp_depth.get();\n"
            + "  if (__dp_d > "
            + __dp_limit
            + ") {\n"
            + "    __dp_ok = true; // skip invariant to avoid re-entrancy\n"
            + "  } else {\n"
            + "    __dp_depth.set(__dp_d + 1);\n"
            + "    try {\n"
            + "      __dp_ok = ("
            + expr
            + ");\n"
            + "    } catch (Throwable __dp_inner) {\n"
            + "      __dp_ok = false;\n"
            + "    } finally {\n"
            + "      __dp_depth.set(__dp_d);\n"
            + "    }\n"
            + "  }\n"

            // --------- if fails: record fail JSON once ---------
            + "  if (!__dp_ok) {\n"
            + "    final String __dp_fail_key = \"DP_INV_FAIL_\" + __dp_id;\n"
            + "    if (__dp_props.putIfAbsent(__dp_fail_key, \"1\") == null) {\n"
            + "      __dp_props.setProperty(\n"
            + "        \"DP_INV_FAIL_JSON_\" + __dp_id,\n"
            + "        \"{\\\"type\\\":\\\"INV_FAIL\\\",\\\"id\\\":\\\""
            + id
            + "\\\","
            + "\\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "\\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "\\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "\\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "\\\"error\\\":\\\"\\\"}\"\n"
            + "      );\n"
            + "    }\n"
            + "  }\n"

            // --------- outer catch: record fail JSON once (with error) ---------
            + "} catch (Throwable "
            + exVar
            + ") {\n"
            + "  final java.util.Properties __dp_props = System.getProperties();\n"
            + "  final String __dp_id = \""
            + id
            + "\";\n"
            + "  final String __dp_fail_key = \"DP_INV_FAIL_\" + __dp_id;\n"
            + "  if (__dp_props.putIfAbsent(__dp_fail_key, \"1\") == null) {\n"
            + "    __dp_props.setProperty(\n"
            + "      \"DP_INV_FAIL_JSON_\" + __dp_id,\n"
            + "      (\"{\\\"type\\\":\\\"INV_FAIL\\\",\\\"id\\\":\\\""
            + id
            + "\\\","
            + "\\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "\\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "\\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "\\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "\\\"error\\\":\\\"\" + "
            + exVar
            + ".toString().replace(\"\\\\\",\"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\")"
            + " + \"\\\"}\")\n"
            + "    );\n"
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

  private InvariantRecord rewriteResult(InvariantRecord rec, String tmpVar) {
    String newExpr = rec.spec().expression().replaceAll("\\bresult\\b", tmpVar);
    return new InvariantRecord(
        rec.id(),
        new InvariantSpec(newExpr, rec.spec().rationale(), rec.spec().meta()),
        rec.point(),
        rec.sourceFile(),
        rec.createdAt());
  }

  // ======================================================================================
  // CONTEXT FILTERING
  // ======================================================================================

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
