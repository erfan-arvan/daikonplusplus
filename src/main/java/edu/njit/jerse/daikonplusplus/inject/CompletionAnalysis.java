package edu.njit.jerse.daikonplusplus.inject;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import java.util.List;
import java.util.Optional;

/**
 * Syntactic "can complete normally" analysis for statements, following the reachability rules of
 * JLS §14.22.
 *
 * <p>Used to decide whether code appended after a method body's last statement would be reachable.
 * Constant conditions are recognized only when built from boolean literals ({@code while (true)},
 * {@code for (;;)}, {@code while (!false)}); conditions that are constant only through named
 * constants are treated as non-constant.
 */
final class CompletionAnalysis {

  private CompletionAnalysis() {}

  /**
   * Returns whether the given statement can complete normally.
   *
   * @param s statement to analyze
   * @return true if control can flow past the end of the statement
   */
  static boolean canCompleteNormally(Statement s) {
    if (s instanceof BlockStmt) {
      return allCompleteNormally(((BlockStmt) s).getStatements());
    }
    if (s instanceof ReturnStmt
        || s instanceof ThrowStmt
        || s instanceof BreakStmt
        || s instanceof ContinueStmt
        || s instanceof YieldStmt) {
      return false;
    }
    if (s instanceof IfStmt) {
      IfStmt is = (IfStmt) s;
      Optional<Statement> elseStmt = is.getElseStmt();
      if (elseStmt.isEmpty()) {
        return true;
      }
      return canCompleteNormally(is.getThenStmt()) || canCompleteNormally(elseStmt.get());
    }
    if (s instanceof WhileStmt) {
      WhileStmt ws = (WhileStmt) s;
      return !isConstantTrue(ws.getCondition()) || hasBreakTargeting(ws);
    }
    if (s instanceof DoStmt) {
      DoStmt ds = (DoStmt) s;
      boolean bodyOrContinue = canCompleteNormally(ds.getBody()) || hasContinueTargeting(ds);
      return (bodyOrContinue && !isConstantTrue(ds.getCondition())) || hasBreakTargeting(ds);
    }
    if (s instanceof ForStmt) {
      ForStmt fs = (ForStmt) s;
      boolean infinite = fs.getCompare().map(CompletionAnalysis::isConstantTrue).orElse(true);
      return !infinite || hasBreakTargeting(fs);
    }
    if (s instanceof ForEachStmt) {
      return true;
    }
    if (s instanceof LabeledStmt) {
      LabeledStmt ls = (LabeledStmt) s;
      return canCompleteNormally(ls.getStatement()) || hasLabeledBreakTargeting(ls);
    }
    if (s instanceof SynchronizedStmt) {
      return canCompleteNormally(((SynchronizedStmt) s).getBody());
    }
    if (s instanceof TryStmt) {
      TryStmt ts = (TryStmt) s;
      if (ts.getFinallyBlock().isPresent() && !canCompleteNormally(ts.getFinallyBlock().get())) {
        return false;
      }
      if (canCompleteNormally(ts.getTryBlock())) {
        return true;
      }
      for (CatchClause cc : ts.getCatchClauses()) {
        if (canCompleteNormally(cc.getBody())) {
          return true;
        }
      }
      return false;
    }
    if (s instanceof SwitchStmt) {
      return switchCanCompleteNormally((SwitchStmt) s);
    }
    // Expression, empty, assert, local class/record declarations, explicit constructor calls.
    return true;
  }

  private static boolean allCompleteNormally(List<Statement> stmts) {
    for (Statement st : stmts) {
      if (!canCompleteNormally(st)) {
        return false;
      }
    }
    return true;
  }

  private static boolean switchCanCompleteNormally(SwitchStmt sw) {
    List<SwitchEntry> entries = sw.getEntries();
    if (entries.isEmpty() || hasBreakTargeting(sw)) {
      return true;
    }
    boolean hasDefault = entries.stream().anyMatch(e -> e.isDefault() || e.getLabels().isEmpty());
    if (!hasDefault) {
      return true;
    }
    boolean arrowForm = entries.get(0).getType() != SwitchEntry.Type.STATEMENT_GROUP;
    if (!arrowForm) {
      // Old-style groups fall through, so only the last group decides.
      return allCompleteNormally(entries.get(entries.size() - 1).getStatements());
    }
    for (SwitchEntry e : entries) {
      switch (e.getType()) {
        case EXPRESSION:
          return true;
        case THROWS_STATEMENT:
          break;
        default:
          if (allCompleteNormally(e.getStatements())) {
            return true;
          }
      }
    }
    return false;
  }

  /** True for conditions that are constant {@code true} using only boolean literals. */
  private static boolean isConstantTrue(Expression e) {
    return constantValue(e).orElse(false);
  }

  private static Optional<Boolean> constantValue(Expression e) {
    if (e instanceof BooleanLiteralExpr) {
      return Optional.of(((BooleanLiteralExpr) e).getValue());
    }
    if (e instanceof EnclosedExpr) {
      return constantValue(((EnclosedExpr) e).getInner());
    }
    if (e instanceof UnaryExpr
        && ((UnaryExpr) e).getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
      return constantValue(((UnaryExpr) e).getExpression()).map(v -> !v);
    }
    if (e instanceof BinaryExpr) {
      BinaryExpr be = (BinaryExpr) e;
      Optional<Boolean> l = constantValue(be.getLeft());
      Optional<Boolean> r = constantValue(be.getRight());
      if (l.isEmpty() || r.isEmpty()) {
        return Optional.empty();
      }
      boolean a = l.get();
      boolean b = r.get();
      switch (be.getOperator()) {
        case AND:
        case BINARY_AND:
          return Optional.of(a && b);
        case OR:
        case BINARY_OR:
          return Optional.of(a || b);
        case XOR:
        case NOT_EQUALS:
          return Optional.of(a != b);
        case EQUALS:
          return Optional.of(a == b);
        default:
          return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** Is there an unlabeled {@code break} whose nearest enclosing loop/switch is {@code target}? */
  @SuppressWarnings("interned")
  private static boolean hasBreakTargeting(Statement target) {
    for (BreakStmt b : target.findAll(BreakStmt.class)) {
      if (b.getLabel().isEmpty() && nearestBreakable(b, target) == target) {
        return true;
      }
    }
    return false;
  }

  /** Is there an unlabeled {@code continue} whose nearest enclosing loop is {@code loop}? */
  @SuppressWarnings("interned")
  private static boolean hasContinueTargeting(Statement loop) {
    for (ContinueStmt c : loop.findAll(ContinueStmt.class)) {
      if (c.getLabel().isEmpty() && nearestLoop(c, loop) == loop) {
        return true;
      }
    }
    return false;
  }

  /** Is there a {@code break label} targeting this labeled statement? */
  private static boolean hasLabeledBreakTargeting(LabeledStmt ls) {
    String label = ls.getLabel().asString();
    for (BreakStmt b : ls.findAll(BreakStmt.class)) {
      if (b.getLabel().isPresent()
          && b.getLabel().get().asString().equals(label)
          && !crossesBoundary(b, ls)) {
        return true;
      }
    }
    return false;
  }

  /** Nearest enclosing loop or switch statement of {@code n}, searching up to {@code limit}. */
  @SuppressWarnings("interned")
  private static Node nearestBreakable(Node n, Node limit) {
    Node cur = n;
    while (cur.getParentNode().isPresent() && cur != limit) {
      cur = cur.getParentNode().get();
      if (isBoundary(cur)) {
        return cur;
      }
      if (cur instanceof WhileStmt
          || cur instanceof DoStmt
          || cur instanceof ForStmt
          || cur instanceof ForEachStmt
          || cur instanceof SwitchStmt) {
        return cur;
      }
    }
    return cur;
  }

  /** Nearest enclosing loop of {@code n}, searching up to {@code limit}. */
  @SuppressWarnings("interned")
  private static Node nearestLoop(Node n, Node limit) {
    Node cur = n;
    while (cur.getParentNode().isPresent() && cur != limit) {
      cur = cur.getParentNode().get();
      if (isBoundary(cur)) {
        return cur;
      }
      if (cur instanceof WhileStmt
          || cur instanceof DoStmt
          || cur instanceof ForStmt
          || cur instanceof ForEachStmt) {
        return cur;
      }
    }
    return cur;
  }

  @SuppressWarnings("interned")
  private static boolean crossesBoundary(Node n, Node limit) {
    Node cur = n;
    while (cur.getParentNode().isPresent() && cur != limit) {
      cur = cur.getParentNode().get();
      if (cur != limit && isBoundary(cur)) {
        return true;
      }
    }
    return false;
  }

  /** Nodes that break/continue cannot jump out of. */
  private static boolean isBoundary(Node n) {
    return n instanceof LambdaExpr
        || n instanceof SwitchExpr
        || n instanceof TypeDeclaration
        || n instanceof LocalClassDeclarationStmt
        || n instanceof LocalRecordDeclarationStmt
        || (n instanceof ObjectCreationExpr
            && ((ObjectCreationExpr) n).getAnonymousClassBody().isPresent());
  }
}
