package edu.njit.jerse.daikonplusplus.filter;

import edu.njit.jerse.daikonplusplus.JavaRunner;
import edu.njit.jerse.daikonplusplus.results.LogParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Test-based invariant filtering that removes invariants causing test failures.
 *
 * <p>Strategy: failures are caught <em>online</em>, as a run's output streams, starting from the
 * very first run of the suite — not discovered after the fact by scanning a completed log. The
 * caller (see {@code App}'s main recovery loop) watches every run in real time for any recognized
 * {@link TestFailureLogParser} signature and kills the process the instant one appears; if that
 * happened, the already-known failure is passed in directly via {@code preKnownFailure} and this
 * method skips straight to isolating its cause. Only when no failure was ever caught live (e.g. the
 * caller doesn't watch, or a failure format slips through undetected until the run's natural exit)
 * does this method fall back to the old post-hoc path: exit code first, and only if non-zero,
 * scanning the completed log once for a recognized signature.
 *
 * <p>If a failure is confirmed, the candidate pool of invariants to search over is seeded directly
 * from what already executed — from the initial run's shm directory when the failure was caught
 * online mid-run (the most reliable source, since a killed process never gets to run its normal
 * shutdown-hook log sidecar), or otherwise from the completed initial run's log (every {@code
 * INV_EXD} entry) — <em>not</em> by rerunning the whole suite again, since that run already paid
 * the cost of executing everything up to that point and already recorded the result.
 *
 * <p>From there, <a href="https://www.debuggingbook.org/html/DeltaDebugger.html">delta
 * debugging</a> ({@code ddmin}) isolates a 1-minimal culprit set: candidates are disabled/enabled
 * in shrinking, targeted subsets, each checked with a real-time monitored rerun on its own fresh,
 * disposable clone of the project — never a shared, progressively-mutated working copy, so a
 * rerun's environment can never be polluted by whatever an earlier rerun left behind — as the
 * runner's output streams in, each line is checked against the same failure signature, and the
 * instant the <em>same</em> failure (see {@link TestFailureLogParser#isSameFailure}) reappears, the
 * process is killed immediately rather than waiting for the rest of the trial's suite run to
 * finish.
 *
 * <p>Before trusting that search, each failure is sanity-checked: disabling <em>every</em>
 * candidate invariant must actually make it stop reproducing. If it doesn't — e.g. a build/lint
 * failure (a Checkstyle rule, say) that no invariant's enabled/disabled state could ever affect —
 * the failure isn't attributable to any invariant, and the search for it stops rather than
 * pretending {@code ddmin} found a meaningful minimal set.
 *
 * <p>A project can have more than one distinct failing test. After isolating and disabling one
 * failure's culprit set, the suite is rerun once more; if a <em>different</em> recognized failure
 * shows up, the whole process repeats for it — seeding a fresh candidate pool, sanity-checking, and
 * running {@code ddmin} again — continuing until a rerun shows no more recognized failures, a
 * failure turns out not to be attributable, or a safety cap on rounds is hit.
 *
 * <p>The process operates on copies of the project to avoid mutating the original injected code and
 * uses marker-based regions to selectively disable invariants.
 */
public final class TestInvariantFilter {

  private static final String ONELINE_BEGIN = "/*__DP_ONELINE_BEGIN__*/";
  private static final String ONELINE_END = "/*__DP_ONELINE_END__*/";
  private static final String BLOCK_BEGIN = "__DP_INVARIANT_BEGIN__";
  private static final String BLOCK_END = "__DP_INVARIANT_END__";

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private TestInvariantFilter() {}

  /** Safety cap on how many distinct failures one call to {@link #run} will chase. */
  private static final int MAX_FAILURE_ROUNDS = 20;

  /**
   * Executes test-based filtering to identify and remove invariants that break tests.
   *
   * @param injectedProjectRoot root of the project with injected invariants
   * @param mainSrcRoot source root containing instrumented Java files
   * @param registryPath registry mapping invariant IDs to program elements
   * @param initialRunLog log from the initial execution, already produced by the caller
   * @param runnerScript external test runner script
   * @param methodBatchSize unused by the current (delta-debugging) strategy; kept for source
   *     compatibility with existing callers/config
   * @param timeoutMinutes wall-clock timeout applied to every rerun, identical to the timeout used
   *     for the original run — see {@link JavaRunner#runExternalScript}
   * @param staleCheckMinutes stale/hang-detection threshold applied to every rerun, identical to
   *     the threshold used for the original run — see {@link JavaRunner#runExternalScript}
   * @param preKnownFailure a failure already caught online (mid-run) by the caller, if any — when
   *     present, the post-hoc exit-code/log-scan check below is skipped entirely, since the caller
   *     already killed that run the instant this failure was seen
   * @param initialShmDir shm directory used by the initial run, if the caller has one — used to
   *     seed round-1 candidates reliably when {@code preKnownFailure} is present (a process killed
   *     mid-run never gets to write its normal log sidecar, so shm is the only complete source);
   *     ignored when {@code preKnownFailure} is empty
   * @return result object describing the final filtered project and removed invariants
   * @throws Exception if execution fails
   */
  public static Result run(
      Path injectedProjectRoot,
      Path mainSrcRoot,
      Path registryPath,
      Path initialRunLog,
      Path runnerScript,
      int methodBatchSize,
      long timeoutMinutes,
      long staleCheckMinutes,
      Optional<TestFailureLogParser.FailureMatch> preKnownFailure,
      @org.checkerframework.checker.nullness.qual.Nullable Path initialShmDir)
      throws Exception {

    System.out.println("\n[DP-TEST-FILTER] ===== START TEST-BASED FILTERING =====");
    System.out.println("[DP-TEST-FILTER] strategy = real-time-detection + delta-debugging");

    Map<UUID, String> idToMethod = readRegistryMethods(registryPath);

    Optional<TestFailureLogParser.FailureMatch> firstFailure;
    List<UUID> round1CandidatesOverride = null;

    if (preKnownFailure.isPresent()) {
      firstFailure = preKnownFailure;
      System.out.println(
          "[DP-TEST-FILTER] Failure already caught online during the initial run — skipping "
              + "post-hoc exit-code/log-scan check: "
              + firstFailure.get().format()
              + " :: "
              + firstFailure.get().line());
      if (initialShmDir != null && Files.isDirectory(initialShmDir.resolve("ex"))) {
        round1CandidatesOverride = new ArrayList<>(LogParser.readExecutedIdsFromShm(initialShmDir));
        System.out.println(
            "[DP-TEST-FILTER] Round 1 candidates seeded from initial run's shm ("
                + round1CandidatesOverride.size()
                + " executed invariant(s) up to the kill point)");
      }
    } else {
      System.out.println("[DP-TEST-FILTER] Checking initial run exit status: " + initialRunLog);

      if (!exitedNonZero(initialRunLog)) {
        System.out.println(
            "[DP-TEST-FILTER] Initial run exited cleanly (exit=0) — nothing to filter "
                + "(skipping log scan and project copy)");
        System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");
        return noopResult(injectedProjectRoot, mainSrcRoot, initialRunLog, 0);
      }

      System.out.println(
          "[DP-TEST-FILTER] Initial run exited non-zero — scanning log for a recognized "
              + "test-failure signature: "
              + initialRunLog);
      String initialLogText = readIfExists(initialRunLog);
      firstFailure = TestFailureLogParser.firstFailure(initialLogText);

      if (firstFailure.isEmpty()) {
        System.out.println(
            "[DP-TEST-FILTER] Initial run failed (exit!=0) but no recognized test-failure "
                + "signature was found — cannot safely attribute to an invariant, nothing to "
                + "filter (skipping project copy)");
        System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");
        return noopResult(injectedProjectRoot, mainSrcRoot, initialRunLog, 1);
      }
    }

    // No single "working copy" is created here anymore. Every individual rerun below (sanity
    // trial, ddmin trial, confirming rerun, and every stale-retry of one) instead clones its own
    // fresh, pristine copy straight from injectedProjectRoot right before it runs, and deletes
    // that copy right after — so a rerun's environment (build caches, incremental-compile state,
    // leftover daemons/ports/temp files from the previous run in that same directory) is always
    // identical to the very first run's, never polluted by whatever the prior rerun left behind.
    // See disableBlocks()/testConfig() below. BlockIndex positions are copy-invariant (same file
    // content, same line numbers in every clone), so it's built once here, up front, straight from
    // the pristine mainSrcRoot — no working copy needed just to scan it.
    BlockIndex idx = scanInvariantBlocks(mainSrcRoot);

    // Lightweight session directory: holds only trial logs and the shared shm scratch dir, never
    // a project copy, so it stays cheap to keep around for the whole call.
    Path sessionDir =
        Files.createTempDirectory(sessionParentDir(injectedProjectRoot), "test-filter-session-");
    Path shmDir = sessionDir.resolve(".daikonpp-test-filter-shm");
    Files.createDirectories(shmDir);
    System.out.println("[DP-TEST-FILTER] Session dir (logs + shm only): " + sessionDir);

    Set<UUID> disabledSoFar = new LinkedHashSet<>();
    List<String> allTrialLog = new ArrayList<>();
    TrialCounter trialCounter = new TrialCounter();
    List<String> failuresHandled = new ArrayList<>();

    Path currentLog = initialRunLog;
    Optional<TestFailureLogParser.FailureMatch> currentFailure = firstFailure;
    int finalExit = 1;
    int round = 0;
    boolean confirmationInconclusive = false;
    // Shared across every rerun in this call — sanity trial, ddmin trials, confirming reruns — so
    // the escalating-threshold strategy matches the main pipeline's exactly (see StaleBudget).
    StaleBudget staleBudget = new StaleBudget(staleCheckMinutes);
    // Candidate pool for the *next* round, seeded from a reliable shm source rather than a
    // (possibly online-killed, and so incomplete) log — round 1 from the initial run's shm (see
    // round1CandidatesOverride above), later rounds from this call's shared shm dir, which is
    // reset immediately before each confirming rerun so its content matches that run exactly.
    List<UUID> nextCandidatesOverride = round1CandidatesOverride;

    while (currentFailure.isPresent() && round < MAX_FAILURE_ROUNDS) {
      round++;
      TestFailureLogParser.FailureMatch target = currentFailure.get();
      List<UUID> candidatesForThisRound = nextCandidatesOverride;
      nextCandidatesOverride = null;
      System.out.println(
          "\n[DP-TEST-FILTER] ----- Round "
              + round
              + ": targeting failure ["
              + target.format()
              + "] :: "
              + target.line()
              + " -----");

      FailureRoundResult roundResult =
          handleOneFailure(
              runnerScript,
              injectedProjectRoot,
              mainSrcRoot,
              idx,
              sessionDir,
              shmDir,
              target,
              currentLog,
              candidatesForThisRound,
              disabledSoFar,
              idToMethod,
              allTrialLog,
              trialCounter,
              round,
              timeoutMinutes,
              staleBudget);

      if (!roundResult.attributable) {
        System.out.println(
            "[DP-TEST-FILTER] Round "
                + round
                + ": failure not attributable to any candidate invariant — stopping (this "
                + "failure, and any behind it in the suite, will remain unaddressed)");
        break;
      }

      disabledSoFar.addAll(roundResult.culprits);
      failuresHandled.add(
          "Round "
              + round
              + ": ["
              + target.format()
              + "] :: "
              + target.line()
              + " -> disabled "
              + roundResult.culprits.size()
              + " invariant(s) in "
              + roundResult.trialsUsed
              + " trial(s)");

      // Confirming rerun with every culprit found so far disabled, watched online for *any*
      // recognized failure (no specific target — which failure, if any, shows up next isn't known
      // in advance) so it's caught and killed the instant it appears, just like the very first run.
      // Retries (with an escalating stale threshold) whenever the run is inconclusive — killed by
      // an unrelated stale/hung test rather than by actually finishing — so that never gets
      // silently mistaken for "no failures remain".
      ConfirmResult confirm =
          confirmRoundClean(
              runnerScript,
              injectedProjectRoot,
              mainSrcRoot,
              idx,
              disabledSoFar,
              sessionDir,
              shmDir,
              round,
              timeoutMinutes,
              staleBudget);
      finalExit = confirm.exit;
      currentLog = confirm.logPath;

      if (confirm.runResult == JavaRunner.RunResult.STALE_KILLED
          || confirm.runResult == JavaRunner.RunResult.HARD_TIMEOUT) {
        System.out.println(
            "[DP-TEST-FILTER] Round "
                + round
                + ": could not confirm the suite's status — stopping without claiming success "
                + "(invariants disabled so far in this round are kept, since they were isolated "
                + "against real evidence; whether anything remains unverified)");
        confirmationInconclusive = true;
        break;
      }

      if (confirm.exit == 0) {
        System.out.println("[DP-TEST-FILTER] Round " + round + ": PASSED — no failures remain");
        currentFailure = Optional.empty();
        continue;
      }

      // Reset immediately before this confirming run, so shm/ex now holds exactly what executed
      // during it — reliable even if the run was killed online (a killed process never gets to
      // write its normal log sidecar, so shm is the source of truth here, same as round 1).
      if (Files.isDirectory(shmDir.resolve("ex"))) {
        nextCandidatesOverride = new ArrayList<>(LogParser.readExecutedIdsFromShm(shmDir));
      }

      Optional<TestFailureLogParser.FailureMatch> next = confirm.matched;

      if (next.isPresent() && TestFailureLogParser.isSameFailure(target, next.get())) {
        System.out.println(
            "[DP-TEST-FILTER] Round "
                + round
                + ": the same failure is still present after disabling its isolated culprit "
                + "set — stopping to avoid looping");
        break;
      }

      currentFailure = next;
    }

    if (round >= MAX_FAILURE_ROUNDS && currentFailure.isPresent()) {
      System.out.println(
          "[DP-TEST-FILTER] Reached the "
              + MAX_FAILURE_ROUNDS
              + "-round safety cap with a failure still present — stopping");
    }

    System.out.println("\n[DP-TEST-FILTER] ===== SUMMARY =====");
    System.out.println("[DP-TEST-FILTER] failures handled: " + failuresHandled.size());
    for (String f : failuresHandled) {
      System.out.println("[DP-TEST-FILTER]   " + f);
    }
    System.out.println("[DP-TEST-FILTER] total invariants disabled: " + disabledSoFar.size());
    System.out.println("[DP-TEST-FILTER] total ddmin trials: " + trialCounter.count);
    if (confirmationInconclusive) {
      System.out.println(
          "[DP-TEST-FILTER] WARNING: the last round's confirming rerun never reached a "
              + "conclusive pass/fail outcome (repeated unrelated stale-kills) — the suite's "
              + "current clean/failing status is UNVERIFIED, not confirmed clean");
    }
    // One last pristine clone, with exactly the final disabled set applied — this is the only
    // project copy that survives the call; every clone made for an individual trial/confirm rerun
    // above was already deleted right after that rerun finished.
    Path finalProject = freshCopy(injectedProjectRoot, "test-filter-final");
    Path finalMainSrc =
        finalProject.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();
    disableBlocks(idx, disabledSoFar, finalMainSrc);

    System.out.println("[DP-TEST-FILTER] final project=" + finalProject);
    System.out.println("[DP-TEST-FILTER] final log=" + currentLog);
    System.out.println("[DP-TEST-FILTER] ===== END TEST-BASED FILTERING =====\n");

    return new Result(
        injectedProjectRoot,
        finalProject,
        finalMainSrc,
        currentLog,
        disabledSoFar,
        allTrialLog,
        finalExit);
  }

  /** Outcome of chasing a single failure down to a minimal culprit set (or ruling it out). */
  private static final class FailureRoundResult {
    final boolean attributable;
    final List<UUID> culprits;
    final int trialsUsed;

    FailureRoundResult(boolean attributable, List<UUID> culprits, int trialsUsed) {
      this.attributable = attributable;
      this.culprits = culprits;
      this.trialsUsed = trialsUsed;
    }
  }

  /**
   * Isolates the minimal set of invariants responsible for one specific failure.
   *
   * <p>Seeds a candidate pool from {@code candidateSourceLog}'s executed-invariants (minus anything
   * already disabled in an earlier round), sanity-checks that disabling every candidate actually
   * makes the failure stop reproducing, and — only if that holds — runs {@code ddmin} to narrow it
   * down.
   *
   * @param candidateSourceLog the most recent completed run's log to seed candidates from, used
   *     unless {@code explicitCandidates} is given
   * @param explicitCandidates when non-null, used as the candidate pool directly instead of reading
   *     {@code candidateSourceLog} — used for round 1 when the failure was caught online mid-run,
   *     so candidates come from the initial run's shm state instead of a (possibly incomplete,
   *     since the process was killed) log
   * @param alreadyDisabled invariants disabled in earlier rounds (excluded from this round's pool)
   */
  private static FailureRoundResult handleOneFailure(
      Path runnerScript,
      Path injectedProjectRoot,
      Path mainSrcRoot,
      BlockIndex idx,
      Path sessionDir,
      Path shmDir,
      TestFailureLogParser.FailureMatch target,
      Path candidateSourceLog,
      @org.checkerframework.checker.nullness.qual.Nullable List<UUID> explicitCandidates,
      Set<UUID> alreadyDisabled,
      Map<UUID, String> idToMethod,
      List<String> trialLog,
      TrialCounter trialCounter,
      int round,
      long timeoutMinutes,
      StaleBudget staleBudget)
      throws IOException, InterruptedException {

    List<UUID> candidates =
        explicitCandidates != null
            ? new ArrayList<>(explicitCandidates)
            : new ArrayList<>(LogParser.readExecutedIds(candidateSourceLog));
    candidates.removeAll(alreadyDisabled);
    System.out.println(
        "[DP-TEST-FILTER] Round " + round + ": candidate pool size = " + candidates.size());

    if (candidates.isEmpty()) {
      System.out.println(
          "[DP-TEST-FILTER] Round " + round + ": no candidate invariants — cannot attribute");
      return new FailureRoundResult(false, List.of(), 0);
    }

    int sanityTrial = ++trialCounter.count;
    boolean stillFailsWithAllDisabled =
        testConfig(
            sanityTrial,
            "sanity-all-disabled",
            candidates,
            Set.of(),
            candidates,
            runnerScript,
            injectedProjectRoot,
            mainSrcRoot,
            idx,
            alreadyDisabled,
            sessionDir,
            shmDir,
            target,
            trialLog,
            timeoutMinutes,
            staleBudget);

    if (stillFailsWithAllDisabled) {
      System.out.println(
          "[DP-TEST-FILTER] Round "
              + round
              + ": failure still reproduces with every candidate invariant disabled — not "
              + "caused by any injected invariant (e.g. a build/lint failure, or unrelated code) "
              + "— not attributable");
      // Nothing to undo: every trial above ran on its own disposable pristine clone, so
      // injectedProjectRoot/mainSrcRoot were never touched.
      return new FailureRoundResult(false, List.of(), trialCounter.count);
    }

    List<UUID> culprits =
        ddmin(
            runnerScript,
            injectedProjectRoot,
            mainSrcRoot,
            idx,
            alreadyDisabled,
            sessionDir,
            shmDir,
            candidates,
            target,
            idToMethod,
            trialLog,
            trialCounter,
            timeoutMinutes,
            staleBudget);

    for (UUID id : culprits) {
      String method = idToMethod.getOrDefault(id, "(unknown method)");
      trialLog.add(
          "Round "
              + round
              + ": disabled "
              + id
              + " ["
              + method
              + "] — isolated via delta debugging against target ["
              + target.format()
              + "] :: "
              + target.line());
      System.out.println(
          "[DP-TEST-FILTER]   -> Round " + round + " disabled " + id + " [" + method + "]");
    }

    return new FailureRoundResult(true, culprits, trialCounter.count);
  }

  private static Result noopResult(
      Path injectedProjectRoot, Path mainSrcRoot, Path initialRunLog, int exitCode) {
    return new Result(
        injectedProjectRoot,
        injectedProjectRoot,
        mainSrcRoot,
        initialRunLog,
        Set.of(),
        List.of(),
        exitCode);
  }

  // =====================================================================================
  // Delta debugging (ddmin)
  // =====================================================================================

  private static final class TrialCounter {
    int count = 0;
  }

  /** Upper bound on the escalating stale-check threshold — same cap the main pipeline uses. */
  private static final long MAX_STALE_CHECK_MINUTES = 10L;

  /**
   * Shared, ever-escalating stale-check threshold used by every rerun within one {@link #run} call
   * — the sanity trial, every ddmin trial, and every confirming rerun all read and update the same
   * instance. Mirrors the main pipeline's own stale-recovery backoff exactly: doubles (capped at
   * {@link #MAX_STALE_CHECK_MINUTES}) whenever a run is stale-killed or hard-timed-out, and never
   * resets for the rest of the call — so once the environment's real behavior (e.g. how long a
   * clean compile/startup actually takes) is learned from one kill, every later rerun benefits from
   * it instead of repeating the same premature kill at the original threshold.
   */
  private static final class StaleBudget {
    long minutes;

    StaleBudget(long initial) {
      this.minutes = initial;
    }

    void escalate() {
      minutes = Math.min(minutes * 2, MAX_STALE_CHECK_MINUTES);
    }
  }

  /**
   * Isolates a 1-minimal subset of {@code delta} whose presence (enabled, with everything else in
   * {@code delta} disabled) reproduces {@code target}. {@code delta} is assumed to reproduce the
   * failure when fully enabled — that's how it was built (from a run that just reproduced it).
   *
   * <p>Classic {@code ddmin}: split into {@code n} chunks; try each chunk alone; if none
   * reproduces, try each chunk's complement (i.e. removing just that chunk); if that doesn't narrow
   * things either, increase granularity (double {@code n}) and retry, until {@code n} reaches
   * {@code delta.size()} with no further progress — at which point {@code delta} is 1-minimal.
   *
   * @return the isolated minimal culprit set (in original execution order)
   */
  private static List<UUID> ddmin(
      Path runnerScript,
      Path injectedProjectRoot,
      Path mainSrcRoot,
      BlockIndex idx,
      Set<UUID> alreadyDisabled,
      Path sessionDir,
      Path shmDir,
      List<UUID> deltaInit,
      TestFailureLogParser.FailureMatch target,
      Map<UUID, String> idToMethod,
      List<String> trialLog,
      TrialCounter trialCounter,
      long timeoutMinutes,
      StaleBudget staleBudget)
      throws IOException, InterruptedException {

    List<UUID> delta = new ArrayList<>(deltaInit);
    int n = 2;

    while (delta.size() > 1) {
      n = Math.min(n, delta.size());
      // Candidates come from the initial run's executed-invariants log (a set, not an ordered
      // sequence — see the comment where `candidates` is built), so chunk order here carries no
      // proximity-to-failure meaning; it's just a deterministic split, not a search heuristic.
      List<List<UUID>> chunks = splitInto(delta, n);

      boolean reduced = false;

      for (List<UUID> chunk : chunks) {
        if (chunk.isEmpty()) continue;
        int trialNum = ++trialCounter.count;
        boolean fail =
            testConfig(
                trialNum,
                "alone",
                chunk,
                new LinkedHashSet<>(chunk),
                delta,
                runnerScript,
                injectedProjectRoot,
                mainSrcRoot,
                idx,
                alreadyDisabled,
                sessionDir,
                shmDir,
                target,
                trialLog,
                timeoutMinutes,
                staleBudget);
        if (fail) {
          delta = new ArrayList<>(chunk);
          n = 2;
          reduced = true;
          break;
        }
      }

      if (!reduced) {
        for (List<UUID> chunk : chunks) {
          if (chunk.isEmpty() || chunk.size() == delta.size()) continue;
          int trialNum = ++trialCounter.count;
          List<UUID> complement = new ArrayList<>(delta);
          complement.removeAll(chunk);
          boolean fail =
              testConfig(
                  trialNum,
                  "without",
                  chunk,
                  new LinkedHashSet<>(complement),
                  delta,
                  runnerScript,
                  injectedProjectRoot,
                  mainSrcRoot,
                  idx,
                  alreadyDisabled,
                  sessionDir,
                  shmDir,
                  target,
                  trialLog,
                  timeoutMinutes,
                  staleBudget);
          if (fail) {
            delta = complement;
            n = Math.max(n - 1, 2);
            reduced = true;
            break;
          }
        }
      }

      if (!reduced) {
        if (n >= delta.size()) break; // 1-minimal — no chunk alone or complement helps
        n = Math.min(n * 2, delta.size());
      }
    }

    return delta;
  }

  /**
   * Runs one ddmin trial configuration (exactly {@code enabled} from {@code deltaAll} is enabled,
   * the rest — plus everything already disabled in an earlier round — is disabled) on a brand-new
   * pristine clone of {@code injectedProjectRoot}, with real-time monitoring, and records the
   * outcome.
   *
   * <p>Cloning fresh for every single trial (rather than reusing one working copy across all of
   * them, mutating it in place each time) means every rerun's starting environment — build/test
   * caches, incremental-compile state, anything a previous killed run's process tree left behind —
   * is exactly as clean as the very first run's, never accumulating pollution from trials that ran
   * before it in the same directory.
   *
   * @return true if the target failure reproduced with this configuration
   */
  private static boolean testConfig(
      int trialNum,
      String kind,
      List<UUID> chunk,
      Set<UUID> enabled,
      List<UUID> deltaAll,
      Path runnerScript,
      Path injectedProjectRoot,
      Path mainSrcRoot,
      BlockIndex idx,
      Set<UUID> alreadyDisabled,
      Path sessionDir,
      Path shmDir,
      TestFailureLogParser.FailureMatch target,
      List<String> trialLog,
      long timeoutMinutes,
      StaleBudget staleBudget)
      throws IOException, InterruptedException {

    Set<UUID> toDisable = new LinkedHashSet<>(alreadyDisabled);
    for (UUID id : deltaAll) {
      if (!enabled.contains(id)) {
        toDisable.add(id);
      }
    }

    Path trialCopy = freshCopy(injectedProjectRoot, "test-filter-trial");
    try {
      Path trialMainSrc =
          trialCopy.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();
      disableBlocks(idx, toDisable, trialMainSrc);
      resetShmDir(shmDir);

      Path trialLogFile = sessionDir.resolve("daikonpp-test-filter-ddmin" + trialNum + ".log");
      System.out.println(
          "[DP-TEST-FILTER] Trial "
              + trialNum
              + " ("
              + kind
              + " "
              + chunk.size()
              + " of "
              + deltaAll.size()
              + "): executing on pristine clone "
              + trialCopy
              + " (stale threshold "
              + staleBudget.minutes
              + " min), log -> "
              + trialLogFile);

      MonitorResult mr =
          runMonitored(
              runnerScript, trialCopy, trialLogFile, shmDir, target, timeoutMinutes, staleBudget);

      String desc =
          "Trial "
              + trialNum
              + " ("
              + kind
              + " "
              + chunk.size()
              + " of "
              + deltaAll.size()
              + "): "
              + (mr.reproduced ? "FAIL (reproduced)" : "no reproduction")
              + " exit="
              + mr.exitCode;
      trialLog.add(desc);
      System.out.println("[DP-TEST-FILTER]   -> " + desc);

      return mr.reproduced;
    } finally {
      deleteTreeQuietly(trialCopy);
    }
  }

  /** Splits {@code list} into {@code n} contiguous, roughly-equal, non-empty chunks. */
  private static List<List<UUID>> splitInto(List<UUID> list, int n) {
    List<List<UUID>> chunks = new ArrayList<>();
    int size = list.size();
    int base = size / n;
    int rem = size % n;
    int idx = 0;
    for (int i = 0; i < n && idx < size; i++) {
      int chunkSize = base + (i < rem ? 1 : 0);
      if (chunkSize == 0) continue;
      chunks.add(new ArrayList<>(list.subList(idx, idx + chunkSize)));
      idx += chunkSize;
    }
    return chunks;
  }

  /** Prefix marking a block line commented out by {@link #disableBlocks}. */
  private static final String DISABLED_LINE_PREFIX = "// [DP] test-filter disabled :: ";

  /**
   * Comments out every block in {@code toDisable} within {@code targetMainSrcRoot} — a fresh,
   * pristine clone's source tree, so every line is guaranteed to still hold its original, enabled
   * text before this runs. Unlike the old approach of toggling blocks back and forth in one
   * long-lived working copy, this is one-way (disable only) and never needs to "restore" a block to
   * enabled, since every trial starts from its own untouched clone.
   *
   * @param idx block positions, scanned once from the pristine mainSrcRoot with paths stored
   *     relative to it (see {@link #scanInvariantBlocks}) — resolved here against whichever clone's
   *     {@code targetMainSrcRoot} is passed in
   */
  private static void disableBlocks(BlockIndex idx, Set<UUID> toDisable, Path targetMainSrcRoot)
      throws IOException {

    Map<Path, List<UUID>> byFile = new HashMap<>();
    for (UUID id : toDisable) {
      Block b = idx.blocks.get(id);
      if (b == null) continue;
      byFile.computeIfAbsent(b.file, __ -> new ArrayList<>()).add(id);
    }

    for (Map.Entry<Path, List<UUID>> e : byFile.entrySet()) {
      Path file = targetMainSrcRoot.resolve(e.getKey()).normalize();
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

      for (UUID id : e.getValue()) {
        Block b = idx.blocks.get(id);
        if (b == null) continue;

        for (int lineIdx = b.beginLine; lineIdx <= b.endLine && lineIdx < lines.size(); lineIdx++) {
          lines.set(lineIdx, DISABLED_LINE_PREFIX + lines.get(lineIdx));
        }
      }

      Files.write(file, lines, StandardCharsets.UTF_8);
    }
  }

  // =====================================================================================
  // Real-time monitored execution (kill-on-detect)
  // =====================================================================================

  /** Outcome of a real-time monitored run: whether the target failure reproduced, and how. */
  private static final class MonitorResult {
    final boolean reproduced;
    final int exitCode;
    final Optional<TestFailureLogParser.FailureMatch> matched;
    final JavaRunner.RunResult runResult;

    MonitorResult(
        boolean reproduced,
        int exitCode,
        Optional<TestFailureLogParser.FailureMatch> matched,
        JavaRunner.RunResult runResult) {
      this.reproduced = reproduced;
      this.exitCode = exitCode;
      this.matched = matched;
      this.runResult = runResult;
    }
  }

  /**
   * Runs the external test runner via {@link JavaRunner#runExternalScript}, the exact same shared
   * run path (and its existing stale/hang-detector + hard-timeout protection) used for the original
   * run — with real-time failure detection layered on as an addition to that existing path, not a
   * separate implementation. The instant a streamed line matches the <em>same</em> failure (see
   * {@link TestFailureLogParser#isSameFailure}), the process is killed immediately — no need to
   * wait for the rest of the suite.
   *
   * <p>Uses (and, on a stale-kill or hard-timeout, escalates) the shared {@link StaleBudget} — the
   * same ever-increasing threshold strategy the main pipeline uses for its own first run, applied
   * here across every trial rerun too, so a threshold that turns out to be too tight for this
   * project's normal (non-hung) behavior only ever gets hit once before every later rerun benefits
   * from the higher value.
   *
   * @param script external test runner script
   * @param workDir working directory for execution
   * @param runLog output log file
   * @param shmDir shm directory for this run (already reset by the caller)
   * @param target the failure being chased; a run "reproduces" only if the same failure recurs
   * @param timeoutMinutes wall-clock timeout, identical to the original run's
   * @param staleBudget shared, escalating stale-check threshold (read for this call, and escalated
   *     in place if this call is stale-killed or hard-timed-out)
   * @return whether the target failure reproduced, and the run's exit code
   */
  private static MonitorResult runMonitored(
      Path script,
      Path workDir,
      Path runLog,
      Path shmDir,
      TestFailureLogParser.FailureMatch target,
      long timeoutMinutes,
      StaleBudget staleBudget)
      throws IOException, InterruptedException {

    JavaRunner.RunResult result =
        JavaRunner.runExternalScript(
            script, workDir, "", runLog, timeoutMinutes, staleBudget.minutes, null, shmDir, target);

    if (result == JavaRunner.RunResult.STALE_KILLED
        || result == JavaRunner.RunResult.HARD_TIMEOUT) {
      staleBudget.escalate();
      System.out.println(
          "[DP-TEST-FILTER] Stale threshold escalated to " + staleBudget.minutes + " min");
    }

    boolean reproduced = result == JavaRunner.RunResult.TEST_FAILURE_KILLED;
    Optional<TestFailureLogParser.FailureMatch> matched = Optional.empty();

    if (reproduced) {
      // The kill was already confirmed line-by-line inside runExternalScript; re-derive the
      // matched line from the log for logging/diagnostics purposes.
      matched = TestFailureLogParser.firstFailure(readIfExists(runLog));
    } else if (result == JavaRunner.RunResult.NORMAL) {
      // Safety net: exited non-zero without the real-time path catching a matching line (e.g.
      // buffering). Do one full-log scan before concluding it didn't reproduce.
      String fullText = readIfExists(runLog);
      Optional<TestFailureLogParser.FailureMatch> fallback =
          TestFailureLogParser.firstFailure(fullText);
      if (fallback.isPresent() && TestFailureLogParser.isSameFailure(target, fallback.get())) {
        reproduced = true;
        matched = fallback;
      }
    }

    // runExternalScript doesn't return the child's raw exit code; recover pass/fail from the same
    // marker it appends to the log whenever the exit code is non-zero (exitedNonZero() below),
    // which every other exit-status check in this class already relies on.
    int exit =
        reproduced
            ? -1
            : (result == JavaRunner.RunResult.NORMAL && !exitedNonZero(runLog) ? 0 : -1);

    return new MonitorResult(reproduced, exit, matched, result);
  }

  // =====================================================================================
  // Shared helpers
  // =====================================================================================

  /**
   * Deletes and recreates {@code ex/}, {@code fail/}, and {@code current/} under {@code shmDir}.
   */
  private static void resetShmDir(Path shmDir) throws IOException {
    for (String sub : new String[] {"ex", "fail", "current"}) {
      Path dir = shmDir.resolve(sub);
      if (Files.isDirectory(dir)) {
        try (var s = Files.list(dir)) {
          for (Path p : (Iterable<Path>) s::iterator) {
            Files.deleteIfExists(p);
          }
        }
      }
      Files.createDirectories(dir);
    }
  }

  private static String readIfExists(Path file) throws IOException {
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  /**
   * Marker {@link edu.njit.jerse.daikonplusplus.JavaRunner} appends to a run log, once, only when
   * the external runner exits with a non-zero code (see {@code JavaRunner.runExternalScript}).
   */
  private static final String NON_ZERO_EXIT_MARKER = "[DP] External runner exited with code";

  private static final int TAIL_CHECK_BYTES = 8192;

  /**
   * Cheaply determines whether a run's process exited non-zero, without reading the whole log file
   * — only the last {@link #TAIL_CHECK_BYTES} bytes are inspected for {@link
   * #NON_ZERO_EXIT_MARKER}, which the runner always appends at the very end of the log when its
   * exit code is non-zero.
   *
   * @param runLog the run's log file
   * @return true if the marker was found in the file's tail (exit was non-zero)
   * @throws IOException if the file cannot be read
   */
  private static boolean exitedNonZero(Path runLog) throws IOException {
    if (!Files.isRegularFile(runLog)) return false;

    long size = Files.size(runLog);
    int tailSize = (int) Math.min(size, TAIL_CHECK_BYTES);
    if (tailSize == 0) return false;

    byte[] buf = new byte[tailSize];
    try (RandomAccessFile raf = new RandomAccessFile(runLog.toFile(), "r")) {
      raf.seek(size - tailSize);
      raf.readFully(buf);
    }

    return new String(buf, StandardCharsets.UTF_8).contains(NON_ZERO_EXIT_MARKER);
  }

  /**
   * Scans source files to locate invariant blocks and associate them with UUIDs.
   *
   * <p>Blocks are identified using begin/end markers and mapped to their source file locations,
   * stored relative to {@code mainSrcRoot} (see {@link Block}) so the resulting index is reusable
   * against any later clone of this same source tree, not just the one scanned here.
   *
   * @param mainSrcRoot root of the source tree
   * @return index mapping invariant IDs to source blocks
   * @throws IOException if file traversal fails
   */
  private static BlockIndex scanInvariantBlocks(Path mainSrcRoot) throws IOException {
    BlockIndex index = new BlockIndex();

    try (var walk = Files.walk(mainSrcRoot)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        int i = 0;
        while (i < lines.size()) {
          String line = lines.get(i);

          boolean begin = line.contains(ONELINE_BEGIN) || line.contains(BLOCK_BEGIN);

          if (!begin) {
            i++;
            continue;
          }

          int beginLine = i;
          int endLine = -1;

          for (int j = i; j < lines.size(); j++) {
            String s = lines.get(j);
            if (s.contains(ONELINE_END) || s.contains(BLOCK_END)) {
              endLine = j;
              break;
            }
          }

          if (endLine < 0) {
            i++;
            continue;
          }

          StringBuilder blockText = new StringBuilder();
          for (int j = beginLine; j <= endLine; j++) {
            blockText.append(lines.get(j)).append('\n');
          }

          Matcher m = UUID_PATTERN.matcher(blockText.toString());
          while (m.find()) {
            UUID id = UUID.fromString(m.group());
            index.blocks.put(id, new Block(id, mainSrcRoot.relativize(file), beginLine, endLine));
          }

          i = endLine + 1;
        }
      }
    }

    return index;
  }

  /**
   * Reads invariant-to-method mappings from the registry file.
   *
   * @param registryPath path to registry file
   * @return mapping from invariant UUID to method identifier
   * @throws IOException if reading fails
   */
  private static Map<UUID, String> readRegistryMethods(Path registryPath) throws IOException {
    Map<UUID, String> out = new HashMap<>();

    if (!Files.isRegularFile(registryPath)) return out;

    for (String line : Files.readAllLines(registryPath, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;

      Optional<String> idStr = extract(line, "\"id\":\"", "\"");
      Optional<String> element = extract(line, "\"element\":\"", "\"");

      if (idStr.isEmpty() || element.isEmpty()) continue;

      try {
        out.put(UUID.fromString(idStr.get()), unescapeJson(element.get()));
      } catch (IllegalArgumentException ignored) {
      }
    }

    return out;
  }

  /**
   * Extracts a substring between two delimiters.
   *
   * @param s source string
   * @param start starting delimiter
   * @param end ending delimiter
   * @return extracted substring if present
   */
  private static Optional<String> extract(String s, String start, String end) {
    int i = s.indexOf(start);
    if (i < 0) return Optional.empty();

    int from = i + start.length();
    int j = s.indexOf(end, from);
    if (j < 0) return Optional.empty();

    return Optional.of(s.substring(from, j));
  }

  /**
   * Performs minimal unescaping of JSON string values.
   *
   * @param s escaped string
   * @return unescaped string
   */
  private static String unescapeJson(String s) {
    return s.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  /**
   * Outcome of a confirming rerun: exit status, any failure caught online, and the raw {@link
   * JavaRunner.RunResult} — callers need the latter to tell a genuine pass/fail signal apart from
   * an inconclusive stale-kill/timeout (which proves nothing about whether the target failure is
   * gone).
   */
  private static final class ConfirmResult {
    final int exit;
    final Optional<TestFailureLogParser.FailureMatch> matched;
    final JavaRunner.RunResult runResult;
    final Path logPath;

    ConfirmResult(
        int exit,
        Optional<TestFailureLogParser.FailureMatch> matched,
        JavaRunner.RunResult runResult,
        Path logPath) {
      this.exit = exit;
      this.matched = matched;
      this.runResult = runResult;
      this.logPath = logPath;
    }
  }

  /**
   * Executes an external test runner script via {@link JavaRunner#runExternalScript} — the same
   * shared run path (with its stale/hang-detector + hard-timeout protection) used for every other
   * run — watching online for <em>any</em> recognized test-failure signature (no specific target,
   * since which failure — if any — will show up next isn't known in advance) so a subsequent
   * failure is caught and the run killed the instant it appears, exactly like the very first run,
   * rather than only discovered after this rerun finishes and its log is scanned. Used for the
   * confirming reruns once a round's culprit set is already known.
   *
   * @param script executable test runner script
   * @param workDir working directory for execution
   * @param runLog output log file
   * @param shmDir shm directory for this run (already created/reset by the caller)
   * @param timeoutMinutes wall-clock timeout, identical to the original run's
   * @param staleCheckMinutes stale/hang-detection threshold to use for this one call
   * @return exit status (0 clean pass, -1 any non-normal outcome) and the failure caught online, if
   *     any
   * @throws IOException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  private static ConfirmResult runExternalTestRunner(
      Path script,
      Path workDir,
      Path runLog,
      Path shmDir,
      long timeoutMinutes,
      long staleCheckMinutes)
      throws IOException, InterruptedException {

    java.util.concurrent.atomic.AtomicReference<TestFailureLogParser.FailureMatch> matchedOut =
        new java.util.concurrent.atomic.AtomicReference<>();

    JavaRunner.RunResult result =
        JavaRunner.runExternalScript(
            script,
            workDir,
            "",
            runLog,
            timeoutMinutes,
            staleCheckMinutes,
            null,
            shmDir,
            null,
            true,
            matchedOut);

    if (result == JavaRunner.RunResult.TEST_FAILURE_KILLED) {
      return new ConfirmResult(-1, Optional.ofNullable(matchedOut.get()), result, runLog);
    }
    if (result != JavaRunner.RunResult.NORMAL) {
      return new ConfirmResult(-1, Optional.empty(), result, runLog);
    }
    boolean failed = exitedNonZero(runLog);
    return new ConfirmResult(
        failed ? -1 : 0,
        failed ? TestFailureLogParser.firstFailure(readIfExists(runLog)) : Optional.empty(),
        result,
        runLog);
  }

  /** How many times a confirming rerun that's inconclusive (stale/timeout) is retried. */
  private static final int MAX_CONFIRM_STALE_RETRIES = 5;

  /**
   * Runs the round-{@code round} confirming rerun, retrying whenever the run is inconclusive —
   * killed by the stale detector or hard timeout rather than by finishing (cleanly or with a
   * recognized failure). An inconclusive kill proves nothing about whether the target failure is
   * actually gone; some unrelated slow/looping test elsewhere in the suite can trip the stale
   * detector at roughly the same point on every attempt. Silently treating that as "no failure
   * found" would falsely declare the round done without ever having confirmed a clean run.
   *
   * <p>Uses (and, on every inconclusive attempt, escalates) the same shared {@link StaleBudget}
   * passed down from {@link #run} — the identical ever-increasing-threshold strategy the main
   * pipeline's own first run uses, so a threshold this environment turns out to need (e.g. because
   * a clean compile/startup alone takes longer than the base threshold) is learned once — whether
   * that happens during a ddmin trial or here — and every later rerun in this call, of either kind,
   * benefits from it instead of repeating the same premature kill.
   *
   * @return the first conclusive result (a real pass, a real failure, or a failure caught online),
   *     or — if every retry was also inconclusive — the last inconclusive result, with {@link
   *     ConfirmResult#runResult} still {@code STALE_KILLED}/{@code HARD_TIMEOUT} so the caller can
   *     tell the difference and must not treat it as a clean pass
   */
  private static ConfirmResult confirmRoundClean(
      Path runnerScript,
      Path injectedProjectRoot,
      Path mainSrcRoot,
      BlockIndex idx,
      Set<UUID> disabledSoFar,
      Path sessionDir,
      Path shmDir,
      int round,
      long timeoutMinutes,
      StaleBudget staleBudget)
      throws IOException, InterruptedException {

    for (int attempt = 1; ; attempt++) {
      resetShmDir(shmDir);
      Path roundLog =
          sessionDir.resolve(
              "daikonpp-test-filter-round"
                  + round
                  + "-confirm"
                  + (attempt > 1 ? "-retry" + attempt : "")
                  + ".log");
      System.out.println(
          "[DP-TEST-FILTER] Round "
              + round
              + ": confirming run (attempt "
              + attempt
              + "/"
              + MAX_CONFIRM_STALE_RETRIES
              + ", stale threshold "
              + staleBudget.minutes
              + " min), log -> "
              + roundLog);

      // Fresh pristine clone for this attempt too — an inconclusive stale/hung retry must not
      // reuse the same (possibly now-polluted) directory the previous attempt just got killed in.
      Path trialCopy = freshCopy(injectedProjectRoot, "test-filter-confirm");
      ConfirmResult result;
      try {
        Path trialMainSrc =
            trialCopy.resolve(injectedProjectRoot.relativize(mainSrcRoot)).normalize();
        disableBlocks(idx, disabledSoFar, trialMainSrc);
        result =
            runExternalTestRunner(
                runnerScript, trialCopy, roundLog, shmDir, timeoutMinutes, staleBudget.minutes);
      } finally {
        deleteTreeQuietly(trialCopy);
      }

      boolean inconclusive =
          result.runResult == JavaRunner.RunResult.STALE_KILLED
              || result.runResult == JavaRunner.RunResult.HARD_TIMEOUT;

      if (inconclusive) {
        staleBudget.escalate();
      }

      if (!inconclusive || attempt >= MAX_CONFIRM_STALE_RETRIES) {
        if (inconclusive) {
          System.out.println(
              "[DP-TEST-FILTER] Round "
                  + round
                  + ": confirming run still inconclusive after "
                  + MAX_CONFIRM_STALE_RETRIES
                  + " attempts — giving up; the suite's clean/failing status could not be "
                  + "verified");
        }
        return result;
      }

      System.out.println(
          "[DP-TEST-FILTER] Round "
              + round
              + ": confirming run inconclusive ("
              + result.runResult
              + ") — an unrelated stale/hung test, not the target failure; retrying with stale "
              + "threshold "
              + staleBudget.minutes
              + " min");
    }
  }

  /**
   * Creates a new working copy from an existing snapshot.
   *
   * @param snapshot source snapshot
   * @param prefix prefix for naming the new copy
   * @return path to new copy
   * @throws IOException if copying fails
   */
  private static Path freshCopy(Path snapshot, String prefix) throws IOException {
    Path parent = snapshot.getParent();
    if (parent == null) {
      parent = Path.of(System.getProperty("java.io.tmpdir"));
    }

    Path dst = parent.resolve(prefix + "-" + System.nanoTime());
    copyTree(snapshot, dst);
    return dst;
  }

  /**
   * Recursively copies a directory tree.
   *
   * <p>Prevents copying into overlapping directories.
   *
   * @param from source directory
   * @param to destination directory
   * @throws IOException if copying fails
   */
  private static void copyTree(Path from, Path to) throws IOException {
    Path src = from.toAbsolutePath().normalize();
    Path dst = to.toAbsolutePath().normalize();

    if (dst.startsWith(src) || src.startsWith(dst)) {
      throw new IllegalStateException("Refusing overlapping copy: " + src + " -> " + dst);
    }

    Files.walkFileTree(
        src,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dst.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path target = dst.resolve(src.relativize(file));
            Files.copy(
                file,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Directory a project copy's parent should live under — the same parent {@link #freshCopy} uses
   * for project clones, so the lightweight session directory (logs + shm only) sits alongside them
   * rather than under a filesystem with different space/permissions characteristics.
   */
  private static Path sessionParentDir(Path snapshot) {
    Path parent = snapshot.getParent();
    return parent != null ? parent : Path.of(System.getProperty("java.io.tmpdir"));
  }

  /** Recursively deletes a directory tree, logging (not throwing) on failure. */
  private static void deleteTreeQuietly(Path root) {
    try {
      if (!Files.exists(root)) return;
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      System.err.println(
          "[DP-TEST-FILTER] Warning: failed to delete trial copy " + root + ": " + e.getMessage());
    }
  }

  /** Index of invariant blocks keyed by their UUID. */
  private static final class BlockIndex {
    final Map<UUID, Block> blocks = new HashMap<>();
  }

  /**
   * Represents a contiguous invariant block in a source file.
   *
   * <p>{@code file} is stored <em>relative</em> to the mainSrcRoot the index was scanned from — not
   * absolute — so the same {@link BlockIndex}, built once from the pristine source, can be resolved
   * against any fresh clone's own mainSrcRoot (see {@link #disableBlocks}).
   */
  private static final class Block {
    final UUID id;
    final Path file;
    final int beginLine;
    final int endLine;

    Block(UUID id, Path file, int beginLine, int endLine) {
      this.id = id;
      this.file = file;
      this.beginLine = beginLine;
      this.endLine = endLine;
    }
  }

  /**
   * Result of test-based invariant filtering.
   *
   * <p>Contains the final project state, removed invariants, and execution outcome.
   */
  public static final class Result {
    public final Path snapshotProjectRoot;
    public final Path finalProjectRoot;
    public final Path finalMainSrcRoot;
    public final Path finalRunLog;
    public final Set<UUID> removedIds;
    public final List<String> removedMethodBatches;
    public final int finalExitCode;

    /**
     * @param snapshotProjectRoot initial snapshot of the injected project
     * @param finalProjectRoot project after filtering
     * @param finalMainSrcRoot final main source directory
     * @param finalRunLog log from final test execution
     * @param removedIds set of invariant IDs that were disabled
     * @param removedMethodBatches human-readable descriptions of each disabled invariant and why
     * @param finalExitCode exit code of final test run
     */
    Result(
        Path snapshotProjectRoot,
        Path finalProjectRoot,
        Path finalMainSrcRoot,
        Path finalRunLog,
        Set<UUID> removedIds,
        List<String> removedMethodBatches,
        int finalExitCode) {

      this.snapshotProjectRoot = snapshotProjectRoot;
      this.finalProjectRoot = finalProjectRoot;
      this.finalMainSrcRoot = finalMainSrcRoot;
      this.finalRunLog = finalRunLog;
      this.removedIds = Set.copyOf(removedIds);
      this.removedMethodBatches = List.copyOf(removedMethodBatches);
      this.finalExitCode = finalExitCode;
    }
  }
}
