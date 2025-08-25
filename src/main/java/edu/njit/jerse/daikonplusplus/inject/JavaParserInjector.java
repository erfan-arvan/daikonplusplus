package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import edu.njit.jerse.daikonplusplus.model.InvariantRecord;
import edu.njit.jerse.daikonplusplus.model.InvariantSpec;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import edu.njit.jerse.daikonplusplus.parse.MethodSignatureUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// Performs source-to-source injection of invariant guards using JavaParser.

public final class JavaParserInjector {

  private final FileWriteCoordinator coordinator;

  public JavaParserInjector(FileWriteCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  /**
   * Injects all entry-point invariants for a given source file.
   *
   * @param file the source file to update
   * @param recordsForThisFile invariants that live in this file (ENTRY only)
   */
  public void injectEntryGuards(Path file, List<InvariantRecord> recordsForThisFile)
      throws Exception {
    if (recordsForThisFile.isEmpty()) return;

    coordinator.withFileLock(
        file,
        () -> {
          CompilationUnit cu = StaticJavaParser.parse(file);

          // Group invariants by method descriptor for efficient insertion.
          Map<String, List<InvariantRecord>> byMethod = new HashMap<>();
          for (var rec : recordsForThisFile) {
            if (rec.point().kind() != ProgramPointKind.METHOD_ENTRY) continue;
            byMethod
                .computeIfAbsent(rec.point().elementId().jvmDescriptor(), __ -> new ArrayList<>())
                .add(rec);
          }

          cu.findAll(MethodDeclaration.class, md -> md.getBody().isPresent())
              .forEach(
                  md -> {
                    String desc =
                        md.getNameAsString()
                            + "("
                            + md.getParameters().stream()
                                .map(p -> p.getType().toString())
                                .reduce((a, b) -> a + "," + b)
                                .orElse("")
                            + "):"
                            + md.getType().toString();

                    List<InvariantRecord> list = byMethod.get(desc);
                    if (list == null || list.isEmpty()) return;

                    BlockStmt body = md.getBody().orElseThrow();
                    NodeList<Statement> stmts = body.getStatements();

                    NodeList<Statement> guardStmts = new NodeList<>();
                    for (InvariantRecord rec : list) {
                      guardStmts.add(guardStatement(rec, "ENTRY"));
                    }

                    NodeList<Statement> newStmts = new NodeList<>();
                    newStmts.addAll(guardStmts);
                    newStmts.addAll(stmts);
                    body.setStatements(newStmts);
                  });

          String updated = cu.toString();
          Files.writeString(file, updated, StandardCharsets.UTF_8);
          return null;
        });
  }

  /**
   * Generates a try/catch-wrapped invariant check for the given record.
   *
   * <p>The logger is referenced by its fully-qualified name to avoid import churn.
   */
  private com.github.javaparser.ast.stmt.Statement guardStatement(
      InvariantRecord rec, String phase) {
    final String expr = rec.spec().expression();
    final String id = rec.id().toString();
    // unique catch var per invariant to dodge any local name collisions
    final String exVar = "__dp_ex_" + id.replace("-", "");
    final String code =
        "try {"
            + "  if (!("
            + expr
            + ")) {"
            + "    System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "      \\\"id\\\":\\\""
            + id
            + "\\\","
            + "      \\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "      \\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "      \\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "      \\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "      \\\"error\\\":\\\"\\\"}\");"
            + "  }"
            + "} catch (Throwable "
            + exVar
            + ") {"
            + "  System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "    \\\"id\\\":\\\""
            + id
            + "\\\","
            + "    \\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "    \\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "    \\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "    \\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "    \\\"error\\\":\\\"\" + "
            + exVar
            + ".toString().replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\") + \"\\\"}\");"
            + "}";
    return com.github.javaparser.StaticJavaParser.parseStatement(code);
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public void injectGuards(Path file, List<InvariantRecord> recordsForThisFile) throws Exception {
    if (recordsForThisFile == null || recordsForThisFile.isEmpty()) return;

    coordinator.withFileLock(
        file,
        () -> {
          CompilationUnit cu = StaticJavaParser.parse(file);

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
                        guards.add(guardStatementWithExVar(rec, "ENTRY", exVar));
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
                            // First time we see this return: hoist to a fresh temp
                            block.add(
                                StaticJavaParser.parseStatement(
                                    "final var " + tmp + " = (" + rhs + ");"));
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
                                guardStatementWithExVar(rewriteResult(rec, tmp), "EXIT", exVar));
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
                            block.add(guardStatementWithExVar(rec, "EXIT", exVar));
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
                            body.addStatement(guardStatementWithExVar(rec, "EXIT", exVar));
                          }
                        }
                      }
                    }
                  });

          Files.writeString(file, cu.toString(), StandardCharsets.UTF_8);
          return null;
        });
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  // build a guard, with a UNIQUE catch var
  private Statement guardStatementWithExVar(InvariantRecord rec, String phase, String exVar) {
    final String expr = rec.spec().expression();
    final String id = rec.id().toString();
    final String code =
        "try {"
            + "  if (!("
            + expr
            + ")) {"
            + "    System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "      \\\"id\\\":\\\""
            + id
            + "\\\","
            + "      \\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "      \\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "      \\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "      \\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "      \\\"error\\\":\\\"\\\"}\");"
            + "  }"
            + "} catch (Throwable "
            + exVar
            + ") {"
            + "  System.out.println(\"{\\\"type\\\":\\\"INV_FAIL\\\","
            + "    \\\"id\\\":\\\""
            + id
            + "\\\","
            + "    \\\"element\\\":\\\""
            + esc(rec.point().elementId().toString())
            + "\\\","
            + "    \\\"file\\\":\\\""
            + esc(rec.sourceFile())
            + "\\\","
            + "    \\\"expr\\\":\\\""
            + esc(expr)
            + "\\\","
            + "    \\\"phase\\\":\\\""
            + phase
            + "\\\","
            + "    \\\"error\\\":\\\"\" + "
            + exVar
            + ".toString().replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\",\"\\\\\\\"\") + \"\\\"}\");"
            + "}";
    return StaticJavaParser.parseStatement(code);
  }

  // rewrite 'result' to the given tmp var
  private InvariantRecord rewriteResult(InvariantRecord rec, String tmpVar) {
    String expr = rec.spec().expression().replaceAll("\\bresult\\b", tmpVar);
    InvariantSpec spec = new InvariantSpec(expr, rec.spec().rationale(), rec.spec().meta());
    return new InvariantRecord(rec.id(), spec, rec.point(), rec.sourceFile(), rec.createdAt());
  }
}
