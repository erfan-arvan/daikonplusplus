package edu.njit.jerse.daikonplusplus.inject;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

/** Tests for the JLS §14.22 "can complete normally" analysis used by exit injection. */
public class CompletionAnalysisTest {

  private static boolean completes(String block) {
    return CompletionAnalysis.canCompleteNormally(StaticJavaParser.parseBlock("{" + block + "}"));
  }

  @Test
  public void simpleStatements() {
    assertTrue(completes(""));
    assertTrue(completes("x++;"));
    assertFalse(completes("x++; return;"));
    assertFalse(completes("throw new RuntimeException();"));
    assertFalse(completes("{ { return; } }"));
  }

  @Test
  public void ifStatements() {
    assertTrue(completes("if (c) return;"));
    assertTrue(completes("if (c) return; else x++;"));
    assertFalse(completes("if (c) return; else return;"));
    assertFalse(completes("if (c) { return; } else { throw new RuntimeException(); }"));
    assertFalse(completes("if (a) return; else if (b) return; else return;"));
  }

  @Test
  public void loops() {
    assertTrue(completes("while (c) { return; }"));
    assertFalse(completes("while (true) { x++; }"));
    assertFalse(completes("while ((true)) { x++; }"));
    assertFalse(completes("while (!false) { x++; }"));
    assertTrue(completes("while (true) { if (c) break; }"));
    assertFalse(completes("while (true) { for (int i = 0; i < 3; i++) { break; } }"));
    assertFalse(completes("for (;;) { }"));
    assertTrue(completes("for (;;) { break; }"));
    assertTrue(completes("for (int i = 0; i < n; i++) { return; }"));
    assertTrue(completes("for (Object o : list) { return; }"));
    assertTrue(completes("do { x++; } while (c);"));
    assertFalse(completes("do { return; } while (c);"));
    assertTrue(completes("do { if (c) continue; return; } while (c);"));
    assertFalse(completes("do { x++; } while (true);"));
  }

  @Test
  public void labeledBreaks() {
    assertTrue(completes("outer: while (true) { while (true) { break outer; } }"));
    assertFalse(completes("outer: while (true) { inner: while (true) { break inner; } }"));
  }

  @Test
  public void breaksInsideLambdasOrLocalClassesDoNotCount() {
    assertFalse(completes("while (true) { Runnable r = () -> { for (;;) { break; } }; r.run(); }"));
    assertFalse(
        completes(
            "while (true) { Runnable r = new Runnable() { public void run() { while (true) {"
                + " break; } } }; }"));
  }

  @Test
  public void tryStatements() {
    assertFalse(completes("try { return; } finally { x++; }"));
    assertTrue(completes("try { x++; } catch (RuntimeException e) { return; }"));
    assertFalse(completes("try { return; } catch (RuntimeException e) { throw e; }"));
    assertTrue(completes("try { return; } catch (RuntimeException e) { x++; }"));
    assertFalse(completes("try { x++; } finally { return; }"));
  }

  @Test
  public void switchStatements() {
    assertTrue(completes("switch (n) { case 1: return; }"));
    assertFalse(completes("switch (n) { case 1: return; default: return; }"));
    assertTrue(completes("switch (n) { case 1: break; default: return; }"));
    assertTrue(completes("switch (n) { case 1: return; default: x++; }"));
    assertFalse(
        completes("switch (n) { case 1 -> { return; } default -> throw new RuntimeException(); }"));
    assertTrue(completes("switch (n) { case 1 -> x++; default -> { return; } }"));
  }

  @Test
  public void synchronizedStatements() {
    assertFalse(completes("synchronized (this) { return; }"));
    assertTrue(completes("synchronized (this) { x++; }"));
  }
}
