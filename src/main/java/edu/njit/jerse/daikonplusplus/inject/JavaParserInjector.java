package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
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

          java.util.function.Function<String, String> simple =
              d ->
                  d.replaceAll("\\b([A-Za-z_]\\w*)(?:\\.[A-Za-z_]\\w*)+\\b", "$1")
                      .replace("java.lang.", "");

          // Group invariants by descriptor / simple descriptor
          for (InvariantRecord r : records) {
            String key = r.point().elementId().jvmDescriptor();
            String keySimple = simple.apply(key);
            Map<String, List<InvariantRecord>> target =
                (r.point().kind() == ProgramPointKind.METHOD_ENTRY) ? entryMap : exitMap;

            target.computeIfAbsent(key, __ -> new ArrayList<>()).add(r);
            if (!key.equals(keySimple)) {
              target.computeIfAbsent(keySimple, __ -> new ArrayList<>()).add(r);
            }
          }

          // For each method, attach entry/exit invariants if present
          for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            if (!md.getBody().isPresent()) {
              continue;
            }

            String desc = MethodSignatureUtil.jvmDescriptorBestEffort(md);
            String descSimple = simple.apply(desc);

            List<InvariantRecord> entries = entryMap.get(desc);
            if (entries == null) {
              entries = entryMap.get(descSimple);
            }

            List<InvariantRecord> exits = exitMap.get(desc);
            if (exits == null) {
              exits = exitMap.get(descSimple);
            }

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

    // Lambdas/method refs are dangerous to hoist → skip instrumentation for this return
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
    if (rhs.isNullLiteralExpr()) {
      String t = md.getType().toString();
      return StaticJavaParser.parseStatement("final var " + tmp + " = (" + t + ") null;");
    }
    return StaticJavaParser.parseStatement("final var " + tmp + " = (" + rhs.toString() + ");");
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
            + "  final String __dp_k = \"DP_INV_EXD_"
            + id
            + "\";\n"
            + "  synchronized (System.getProperties()) {\n"
            + "    if (System.getProperty(__dp_k) == null) {\n"
            + "      System.setProperty(__dp_k, \"1\");\n"
            + "      System.out.println(\"INV_EXD:"
            + id
            + "\");\n"
            + "    }\n"
            + "  }\n"
            + "  if (!("
            + expr
            + ")) {\n"
            + "    System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "\\\"id\\\":\\\""
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
            + "\\\"error\\\":\\\"\\\"}\");\n"
            + "  }\n"
            + "} catch (Throwable "
            + exVar
            + ") {\n"
            + "  System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "\\\"id\\\":\\\""
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
            + "\\\"error\\\":\\\"\"+"
            + exVar
            + ".toString().replace(\"\\\\\",\"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\")"
            + "+\"\\\"}\");\n"
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
