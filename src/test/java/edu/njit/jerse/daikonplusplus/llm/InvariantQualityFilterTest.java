package edu.njit.jerse.daikonplusplus.llm;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.config.DpConfig;
import edu.njit.jerse.daikonplusplus.config.QualityFilterRules;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests that each {@link InvariantQualityFilter} rule rejects its target expression by default and
 * stops rejecting it once the corresponding {@link QualityFilterRules} switch is turned off.
 */
public class InvariantQualityFilterTest {

  private static final Map<String, String> SCOPE = Map.of("x", "int", "s", "String");
  private static final Map<String, String> EXIT_SCOPE =
      Map.of("x", "int", "s", "String", "result", "int");

  private static final List<String> RULE_NAMES =
      List.of(
          "maxLength",
          "alwaysTrueLiteral",
          "tautologyNotXOrX",
          "fullRangeComparison",
          "selfComparison",
          "trivialDisjunction",
          "noStatements",
          "noAssignment",
          "forbiddenConstructs",
          "requireInScopeName",
          "requireResultAtExit",
          "primitiveNullComparison",
          "unknownIdentifier",
          "parseCheck");

  /** Builds a rule set with every rule enabled except the named ones. */
  private static QualityFilterRules without(String... disabled) {
    Set<String> off = Set.of(disabled);
    for (String d : off) {
      assertTrue(RULE_NAMES.contains(d), "unknown rule: " + d);
    }
    boolean[] b = new boolean[RULE_NAMES.size()];
    for (int i = 0; i < b.length; i++) {
      b[i] = !off.contains(RULE_NAMES.get(i));
    }
    return new QualityFilterRules(
        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13]);
  }

  /** Asserts the expression is rejected by default and kept once the given rules are disabled. */
  private static void assertToggled(
      String expr, Map<String, String> scope, boolean isExit, String... rules) {
    assertFalse(
        InvariantQualityFilter.keep(expr, scope, isExit, QualityFilterRules.ALL_ENABLED),
        "expected default rules to reject: " + expr);
    assertFalse(
        InvariantQualityFilter.keep(expr, scope, isExit),
        "expected 3-arg overload to reject: " + expr);
    assertTrue(
        InvariantQualityFilter.keep(expr, scope, isExit, without(rules)),
        "expected " + List.of(rules) + " disabled to keep: " + expr);
  }

  @Test
  public void validExpressionIsKeptByDefault() {
    assertTrue(InvariantQualityFilter.keep("x > 0 && s.length() > x", SCOPE, false));
    assertTrue(InvariantQualityFilter.keep("result >= x", EXIT_SCOPE, true));
  }

  @Test
  public void emptyExpressionIsAlwaysRejected() {
    QualityFilterRules allOff = without(RULE_NAMES.toArray(new String[0]));
    assertFalse(InvariantQualityFilter.keep("   ", SCOPE, false, allOff));
  }

  @Test
  public void maxLength() {
    String expr = "x > 1" + " && x > 1".repeat(30);
    assertTrue(expr.length() > 200);
    assertToggled(expr, SCOPE, false, "maxLength");
  }

  @Test
  public void alwaysTrueLiteral() {
    // "true" mentions no in-scope name, so that rule has to be disabled too.
    assertFalse(InvariantQualityFilter.keep("true", SCOPE, false, without("requireInScopeName")));
    assertToggled("true", SCOPE, false, "alwaysTrueLiteral", "requireInScopeName");
  }

  @Test
  public void tautologyNotXOrX() {
    assertToggled("!(x > 0) || (x > 0)", SCOPE, false, "tautologyNotXOrX");
  }

  @Test
  public void fullRangeComparison() {
    assertToggled("x >= Integer.MIN_VALUE", SCOPE, false, "fullRangeComparison");
    assertToggled("x <= Integer.MAX_VALUE", SCOPE, false, "fullRangeComparison");
  }

  @Test
  public void selfComparison() {
    assertToggled("x == x", SCOPE, false, "selfComparison");
  }

  @Test
  public void trivialDisjunction() {
    assertToggled("x == 1 || x != 1 && true", SCOPE, false, "trivialDisjunction");
  }

  @Test
  public void noStatements() {
    assertToggled("s.equals(\";\")", SCOPE, false, "noStatements");
  }

  @Test
  public void noAssignment() {
    assertToggled("(x = 1) > 0", SCOPE, false, "noAssignment");
  }

  @Test
  public void forbiddenConstructs() {
    assertToggled("s.stream() != null", SCOPE, false, "forbiddenConstructs");
  }

  @Test
  public void requireInScopeName() {
    assertToggled("1 < 2", SCOPE, false, "requireInScopeName");
  }

  @Test
  public void requireResultAtExit() {
    assertToggled("x > 0", EXIT_SCOPE, true, "requireResultAtExit");
  }

  @Test
  public void primitiveNullComparison() {
    assertToggled("x != null", SCOPE, false, "primitiveNullComparison");
  }

  @Test
  public void unknownIdentifier() {
    assertToggled("x > y", SCOPE, false, "unknownIdentifier");
  }

  @Test
  public void parseCheck() {
    assertToggled("x > > 0", SCOPE, false, "parseCheck");
  }

  @Test
  public void dpConfigDefaultsToAllRulesEnabledAndHonorsOverrides() {
    QualityFilterRules defaults = DpConfig.fromEnv().qualityFilterRules();
    assertEquals(QualityFilterRules.ALL_ENABLED.toString(), defaults.toString());

    String key = "dp.qualityFilterSelfComparison";
    String prev = System.getProperty(key);
    try {
      System.setProperty(key, "false");
      QualityFilterRules rules = DpConfig.fromEnv().qualityFilterRules();
      assertFalse(rules.selfComparison);
      assertTrue(rules.maxLength);
      assertTrue(rules.parseCheck);
    } finally {
      if (prev == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, prev);
      }
    }
  }
}
