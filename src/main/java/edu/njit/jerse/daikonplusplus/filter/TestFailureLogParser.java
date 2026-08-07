package edu.njit.jerse.daikonplusplus.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects test-failure signatures in the text of an external test runner's log, across several
 * common test-tool output formats.
 *
 * <p>This is a line-by-line, pattern-based scan — it does not rely on the runner process's exit
 * code, since a non-zero exit code alone does not say whether a real test assertion failed versus
 * some unrelated build/tooling problem. Each recognized line is reported as a {@link FailureMatch},
 * tagged with the name of the format that matched it.
 *
 * <p>Recognized formats include (with representative example lines):
 *
 * <ul>
 *   <li>Maven Surefire/Failsafe inline error — {@code [ERROR] Run 1: TestClass.testMethod:157
 *       expected: <100> but was: <99>}
 *   <li>Maven Surefire/Failsafe arrow-style error — {@code [ERROR] TestClass.testMethod:42 »
 *       NullPointer ...}
 *   <li>Gradle/Checkstyle test-event line — {@code SomeClass > someMethod FAILED}
 *   <li>Gradle/JUnit build summary — {@code X tests completed, Y failed}
 *   <li>JUnit5 console-launcher summary — {@code [ 1 tests failed ]}
 *   <li>JUnit5 console-launcher failures header — {@code Failures (1):}
 *   <li>TestNG per-test failure — {@code FAILED: someMethod}
 *   <li>Surefire/JUnit/TestNG numeric summary — {@code Tests run: 5, Failures: 1, Errors: 0,
 *       Skipped: 0}
 *   <li>Generic assertion/comparison exceptions — lines mentioning {@code AssertionError}, {@code
 *       AssertionFailedError}, or {@code ComparisonFailure}
 * </ul>
 *
 * <p>New formats can be added by appending to {@link #FORMATS} — nothing else needs to change.
 */
public final class TestFailureLogParser {

  private TestFailureLogParser() {}

  /** One recognized test-failure output format: a name plus the pattern that identifies it. */
  public record FailureFormat(String name, Pattern pattern) {}

  /** A single matched failure line, with the format that recognized it. */
  public record FailureMatch(String format, int lineNumber, String line) {}

  private static final List<FailureFormat> FORMATS =
      List.of(
          // Maven Surefire/Failsafe arrow style: "TestClass.testMethod:42 » SomeException ..."
          // (checked before the more general inline pattern below, since it's a strict subset)
          new FailureFormat(
              "maven-surefire-arrow",
              Pattern.compile("^\\[ERROR]\\s+[\\w.$]+\\.\\w+:\\d+\\s*».*$")),

          // Maven Surefire/Failsafe: "[ERROR]   Run 1: TestClass.testMethod:157 expected: ..."
          new FailureFormat(
              "maven-surefire-inline",
              Pattern.compile("^\\[ERROR]\\s+(?:Run\\s+\\d+:\\s+)?[\\w.$]+\\.\\w+:\\d+\\s+.*$")),

          // Gradle (and Checkstyle-via-Gradle) test-event line: "Class > method FAILED"
          new FailureFormat(
              "gradle-test-event",
              Pattern.compile("^\\S+(?:\\.\\S+)*\\s*>\\s*\\S+.*\\bFAILED\\s*$")),

          // JUnit5 console-launcher run summary: "[    1 tests failed      ]"
          new FailureFormat(
              "junit5-summary",
              Pattern.compile(
                  "^\\[\\s*[1-9]\\d*\\s+tests?\\s+failed\\s*]\\s*$", Pattern.CASE_INSENSITIVE)),

          // JUnit5 console-launcher failures section header: "Failures (1):"
          new FailureFormat(
              "junit5-failures-header", Pattern.compile("^Failures\\s*\\([1-9]\\d*\\)\\s*:?\\s*$")),

          // TestNG per-test failure line: "FAILED: someMethod"
          new FailureFormat("testng-failed", Pattern.compile("^FAILED:\\s+\\S+.*$")),

          // Surefire/JUnit4/TestNG numeric summary with at least one failure or error.
          new FailureFormat(
              "surefire-summary-failures",
              Pattern.compile("Tests run:\\s*\\d+,\\s*Failures:\\s*[1-9]\\d*")),
          new FailureFormat(
              "surefire-summary-errors",
              Pattern.compile("Tests run:\\s*\\d+,\\s*Failures:\\s*\\d+,\\s*Errors:\\s*[1-9]\\d*")),

          // Generic assertion/comparison exception mentions (JUnit3/4/5, Hamcrest, etc.)
          new FailureFormat(
              "assertion-exception",
              Pattern.compile(
                  ".*\\b(AssertionError|AssertionFailedError|ComparisonFailure|opentest4j\\.\\w+)\\b.*")));

  /**
   * Scans every line of {@code logText} and returns every line that matches a known test-failure
   * format, in the order they appear.
   *
   * @param logText full text of a test-runner log
   * @return all recognized failure matches, earliest first
   */
  public static List<FailureMatch> findFailures(String logText) {
    List<FailureMatch> out = new ArrayList<>();
    if (logText == null || logText.isEmpty()) return out;

    String[] lines = logText.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = stripTrailingCr(lines[i]);
      for (FailureFormat fmt : FORMATS) {
        if (fmt.pattern().matcher(line).find()) {
          out.add(new FailureMatch(fmt.name(), i + 1, line));
          break; // one match per line is enough; avoid double-counting the same line
        }
      }
    }
    return out;
  }

  /**
   * Returns the first recognized test-failure line in {@code logText}, if any.
   *
   * @param logText full text of a test-runner log
   * @return the earliest failure match, or empty if no known format was found
   */
  public static Optional<FailureMatch> firstFailure(String logText) {
    List<FailureMatch> all = findFailures(logText);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  /**
   * Convenience check for whether {@code logText} contains any recognized test-failure signature.
   *
   * @param logText full text of a test-runner log
   * @return true if at least one known failure format matched
   */
  public static boolean hasFailure(String logText) {
    return firstFailure(logText).isPresent();
  }

  private static String stripTrailingCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }
}
