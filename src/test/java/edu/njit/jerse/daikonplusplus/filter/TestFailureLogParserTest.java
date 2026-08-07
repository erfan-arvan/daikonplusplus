package edu.njit.jerse.daikonplusplus.filter;

import static org.junit.jupiter.api.Assertions.*;

import edu.njit.jerse.daikonplusplus.filter.TestFailureLogParser.FailureMatch;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link TestFailureLogParser} reliably recognizes test-failure signatures across
 * different test tools' output formats, and does not fire on unrelated log noise.
 */
public class TestFailureLogParserTest {

  // ---- Maven Surefire/Failsafe ----

  @Test
  public void detectsMavenSurefireInlineRunFailure() {
    String log =
        "[INFO] Running org.apache.hudi.TestHoodieJavaClientOnMergeOnReadStorage\n"
            + "[ERROR]   Run 1: TestHoodieJavaClientOnMergeOnReadStorage.testAsyncCompactionOnMORTable:157 "
            + "expected: <100> but was: <99>\n"
            + "[INFO] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("maven-surefire-inline", match.get().format());
    assertTrue(match.get().line().contains("testAsyncCompactionOnMORTable"));
  }

  @Test
  public void detectsMavenSurefireArrowFailure() {
    String log =
        "[ERROR] com.example.FooTest.bar:42 » NullPointer Cannot invoke \"Object.toString()\"\n";

    assertTrue(TestFailureLogParser.hasFailure(log));

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);
    assertEquals("maven-surefire-arrow", match.get().format());
  }

  @Test
  public void detectsSurefireNumericSummaryWithFailures() {
    String log = "Tests run: 42, Failures: 3, Errors: 0, Skipped: 1\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("surefire-summary-failures", match.get().format());
  }

  @Test
  public void detectsSurefireNumericSummaryWithErrors() {
    String log = "Tests run: 42, Failures: 0, Errors: 2, Skipped: 0\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("surefire-summary-errors", match.get().format());
  }

  @Test
  public void doesNotFireOnCleanSurefireSummary() {
    String log = "Tests run: 42, Failures: 0, Errors: 0, Skipped: 0\n" + "BUILD SUCCESS\n";

    assertFalse(TestFailureLogParser.hasFailure(log));
  }

  // ---- Gradle / Checkstyle-style test events ----

  @Test
  public void detectsGradleTestEventFailure() {
    String log =
        "> Task :test\n" + "SomeTestClass > someMethod FAILED\n" + "    java.lang.AssertionError\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("gradle-test-event", match.get().format());
    assertTrue(match.get().line().contains("SomeTestClass"));
  }

  @Test
  public void detectsCheckstyleStyleGradleFailures() {
    String checkstyleLike =
        "> Task :checkstyleMain\n" + "NewLinesBeforeAnnotation > missingEmptyNewLine FAILED\n";

    assertTrue(TestFailureLogParser.hasFailure(checkstyleLike));
    assertEquals(
        "gradle-test-event", TestFailureLogParser.firstFailure(checkstyleLike).get().format());

    String checkerLike = "NoAnonymousInnerClassesTest > verify FAILED\n";

    assertTrue(TestFailureLogParser.hasFailure(checkerLike));
    assertEquals(
        "gradle-test-event", TestFailureLogParser.firstFailure(checkerLike).get().format());
  }

  @Test
  public void doesNotFireOnGradleTestEventPassed() {
    String log = "> Task :test\n" + "SomeTestClass > someMethod PASSED\n" + "BUILD SUCCESSFUL\n";

    assertFalse(TestFailureLogParser.hasFailure(log));
  }

  // ---- JUnit5 console launcher ----

  @Test
  public void detectsJUnit5SummaryFailedLine() {
    String log = "[         3 tests found      ]\n" + "[         1 tests failed      ]\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("junit5-summary", match.get().format());
  }

  @Test
  public void detectsJUnit5FailuresHeader() {
    String log =
        "Failures (1):\n"
            + "  JUnit Jupiter:SomeTest:someMethod()\n"
            + "    MethodSource [className = 'SomeTest', methodName = 'someMethod']\n"
            + "    => org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n";

    assertTrue(TestFailureLogParser.hasFailure(log));

    List<FailureMatch> matches = TestFailureLogParser.findFailures(log);
    assertTrue(matches.stream().anyMatch(m -> m.format().equals("junit5-failures-header")));
  }

  @Test
  public void doesNotFireOnJUnit5CleanSummary() {
    String log = "[         3 tests found      ]\n" + "[         0 tests failed      ]\n";

    assertFalse(TestFailureLogParser.hasFailure(log));
  }

  // ---- TestNG ----

  @Test
  public void detectsTestNgFailedLine() {
    String log = "PASSED: setUp\n" + "FAILED: testSomething\n" + "PASSED: tearDown\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("testng-failed", match.get().format());
    assertTrue(match.get().line().contains("testSomething"));
  }

  // ---- Generic assertion/exception mentions ----

  @Test
  public void detectsGenericAssertionError() {
    String log = "Exception in thread \"main\" java.lang.AssertionError: invariant violated\n";

    Optional<FailureMatch> match = TestFailureLogParser.firstFailure(log);

    assertTrue(match.isPresent());
    assertEquals("assertion-exception", match.get().format());
  }

  @Test
  public void detectsOpentest4jAssertionFailedError() {
    String log = "Caused by: org.opentest4j.AssertionFailedError: expected: <foo> but was: <bar>\n";

    assertTrue(TestFailureLogParser.hasFailure(log));
  }

  // ---- Multiple failures / ordering ----

  @Test
  public void findFailuresReturnsAllMatchesInLogOrder() {
    String log =
        "SomeTestClass > firstMethod FAILED\n"
            + "OtherTestClass > secondMethod FAILED\n"
            + "Tests run: 10, Failures: 2, Errors: 0, Skipped: 0\n";

    List<FailureMatch> matches = TestFailureLogParser.findFailures(log);

    assertEquals(3, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(2, matches.get(1).lineNumber());
    assertEquals(3, matches.get(2).lineNumber());
    assertTrue(matches.get(0).line().contains("firstMethod"));
    assertTrue(matches.get(1).line().contains("secondMethod"));
  }

  // ---- Negative / edge cases ----

  @Test
  public void emptyLogHasNoFailure() {
    assertFalse(TestFailureLogParser.hasFailure(""));
    assertTrue(TestFailureLogParser.findFailures("").isEmpty());
  }

  @Test
  public void nullLogHasNoFailure() {
    assertFalse(TestFailureLogParser.hasFailure(null));
    assertTrue(TestFailureLogParser.findFailures(null).isEmpty());
  }

  @Test
  public void plainBuildFailedWithoutTestSignatureIsNotTreatedAsTestFailure() {
    // A build/compile failure unrelated to any specific test should not match — we don't
    // want to blame an invariant for something that isn't a test-assertion failure.
    String log = "> Task :compileJava FAILED\n" + "BUILD FAILED in 3s\n";

    assertFalse(TestFailureLogParser.hasFailure(log));
  }

  @Test
  public void doesNotFireOnUnrelatedInfoLogging() {
    String log =
        "[INFO] Scanning for projects...\n"
            + "[INFO] Building daikonplusplus 0.1.0\n"
            + "[INFO] BUILD SUCCESS\n";

    assertFalse(TestFailureLogParser.hasFailure(log));
  }
}
