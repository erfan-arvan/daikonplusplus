package edu.njit.jerse.daikonplusplus.config;

/**
 * Per-rule switches for {@link edu.njit.jerse.daikonplusplus.llm.InvariantQualityFilter}.
 *
 * <p>Every rule is enabled by default; each can be disabled individually through {@link DpConfig}.
 * The global {@code dp.noQualityFilter} switch still bypasses the filter entirely.
 */
public final class QualityFilterRules {

  /** All rules enabled (the historical behavior). */
  public static final QualityFilterRules ALL_ENABLED =
      new QualityFilterRules(
          true, true, true, true, true, true, true, true, true, true, true, true, true, true);

  /** reject expressions longer than 200 characters */
  public final boolean maxLength;

  /** reject the literals {@code true} / {@code (true)} */
  public final boolean alwaysTrueLiteral;

  /** reject {@code !X || X} */
  public final boolean tautologyNotXOrX;

  /** reject {@code >= Integer/Long.MIN_VALUE} and {@code <= Integer/Long.MAX_VALUE} */
  public final boolean fullRangeComparison;

  /** reject self-comparisons such as {@code x == x} */
  public final boolean selfComparison;

  /** reject {@code x == v || x != v} */
  public final boolean trivialDisjunction;

  /** reject expressions containing {@code ;}, <code>{</code> or <code>}</code> */
  public final boolean noStatements;

  /** reject bare assignments ({@code =}) */
  public final boolean noAssignment;

  /** reject streams, lambdas, method refs, {@code new}, {@code throw}, etc. */
  public final boolean forbiddenConstructs;

  /** require at least one in-scope name to be mentioned */
  public final boolean requireInScopeName;

  /** at exit of a non-void method, require {@code result} to be mentioned */
  public final boolean requireResultAtExit;

  /** reject {@code == null} / {@code != null} on primitive-typed names */
  public final boolean primitiveNullComparison;

  /** reject standalone identifiers that are not in scope or an allowed JDK class */
  public final boolean unknownIdentifier;

  /** reject expressions that JavaParser cannot parse */
  public final boolean parseCheck;

  public QualityFilterRules(
      boolean maxLength,
      boolean alwaysTrueLiteral,
      boolean tautologyNotXOrX,
      boolean fullRangeComparison,
      boolean selfComparison,
      boolean trivialDisjunction,
      boolean noStatements,
      boolean noAssignment,
      boolean forbiddenConstructs,
      boolean requireInScopeName,
      boolean requireResultAtExit,
      boolean primitiveNullComparison,
      boolean unknownIdentifier,
      boolean parseCheck) {
    this.maxLength = maxLength;
    this.alwaysTrueLiteral = alwaysTrueLiteral;
    this.tautologyNotXOrX = tautologyNotXOrX;
    this.fullRangeComparison = fullRangeComparison;
    this.selfComparison = selfComparison;
    this.trivialDisjunction = trivialDisjunction;
    this.noStatements = noStatements;
    this.noAssignment = noAssignment;
    this.forbiddenConstructs = forbiddenConstructs;
    this.requireInScopeName = requireInScopeName;
    this.requireResultAtExit = requireResultAtExit;
    this.primitiveNullComparison = primitiveNullComparison;
    this.unknownIdentifier = unknownIdentifier;
    this.parseCheck = parseCheck;
  }

  @Override
  public String toString() {
    return "{maxLength="
        + maxLength
        + ", alwaysTrueLiteral="
        + alwaysTrueLiteral
        + ", tautologyNotXOrX="
        + tautologyNotXOrX
        + ", fullRangeComparison="
        + fullRangeComparison
        + ", selfComparison="
        + selfComparison
        + ", trivialDisjunction="
        + trivialDisjunction
        + ", noStatements="
        + noStatements
        + ", noAssignment="
        + noAssignment
        + ", forbiddenConstructs="
        + forbiddenConstructs
        + ", requireInScopeName="
        + requireInScopeName
        + ", requireResultAtExit="
        + requireResultAtExit
        + ", primitiveNullComparison="
        + primitiveNullComparison
        + ", unknownIdentifier="
        + unknownIdentifier
        + ", parseCheck="
        + parseCheck
        + "}";
  }
}
