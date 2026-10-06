# Daikon++

Daikon++ checks LLM-proposed invariants of a Java program at runtime.

For every **method entry** and **method exit** in your sources, it:

1. asks an LLM for likely boolean invariants (structured output);
2. filters and deduplicates them;
3. injects a runtime guard for each one into a **working copy** of your sources;
4. compiles that copy and runs it (your `main` class, or your own build/test script);
5. reports, for each candidate, whether it **held**, was **falsified**, was **never executed**, or **failed to compile/inject**.

Your original sources are never modified.

---

## Prerequisites

- **JDK 17+** with `javac` and `java`. If `JAVA_HOME` is set, those tools are taken from `$JAVA_HOME/bin`; otherwise from `PATH`.
  The Gradle build uses a Java 17 toolchain (override with `DP_JAVA_VERSION`).
- For real LLM calls: an OpenAI API key in `OPENAI_API_KEY` and network access to the OpenAI API.
  Not needed when replaying recorded responses (cassettes) or in a dry run. A local, Ollama-compatible model can be used instead (see [LLM backends](#llm-backends)).

---

## Build the fat JAR

    ./gradlew clean shadowJar
    # → build/libs/daikonplusplus.jar

`shadowJar` runs the whole test suite (offline replay) first. To build without running the tests:

    ./gradlew shadowJar -x test

---

## Run

There are three ways to invoke Daikon++. In all of them, an optional trailing integer `maxK` is the maximum number of invariants requested per program point (default **5**).

### 1. Single source root

    java -jar build/libs/daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]

- `<srcRoot>` — source tree to instrument (e.g. `/project/src`).
- `<classpath>` — classpath used to compile and run your program. Use `:` as separator on macOS/Linux and `;` on Windows; use `""` or `.` if there are no dependencies. Do **not** include Daikon++ itself.
- `<mainClass>` — fully qualified main class (e.g. `com.example.Main`; for the default package, just `Main`).
- `-- program args...` — everything after `--` is passed to your program unchanged.

Example:

    export OPENAI_API_KEY=...
    java -jar build/libs/daikonplusplus.jar /path/to/app/src . com.example.Main 5 -- foo bar

### 2. Separate main and test sources

    java -jar build/libs/daikonplusplus.jar <mainSrcRoot> <testSrcRoot> <mainClasspath> <testClasspath> <testMainClass> [maxK] [-- program args...]

This mode is chosen automatically when the first two arguments are both existing directories. Only the **main** sources are instrumented. The test sources are compiled against them, and `<testMainClass>` is run.

### 3. External project (your own build/test script)

    java -jar build/libs/daikonplusplus.jar --external-project \
         --project-root <projectRoot> --main-src <relMainSrc> [--test-src <relTestSrc>] \
         --runner-script <script.sh> [maxK]

- The **whole project root** is copied to a working directory, and the main sources (`--main-src`, relative to the project root) are instrumented in that copy.
- Instrumented sources are first compiled by Daikon++ itself to remove candidates that do not compile (see [Pipeline](#what-the-tool-does-pipeline)).
  This uses `javac` with `DP_EXTERNAL_COMPILE_CP` as classpath, or your own script if `DP_COMPILE_MAIN_SCRIPT` is set.
- Then `bash <script.sh>` is run from the root of the working copy. The script path may be absolute or relative to the project root.
- Runtime results are collected through a shared-memory directory (`/dev/shm/daikonpp-<pid>`, or under `DP_SHM_BASE` if set), so they survive child JVMs being killed.
  If a run hangs, the guards that were executing are disabled and the script is run again.

A shorter form, `java -jar daikonplusplus.jar <projectDir> --cmd <script.sh>`, treats `<projectDir>` as both project root and main source root.

### Running with Gradle

    ./gradlew run --args="<srcRoot> <classpath> <mainClass> [maxK] [-- program args...]"

Example:

    ./gradlew run --args="/Users/me/demo/src . Main 5"

---

## Configuration

Every setting can be given in three ways, checked in this order:

1. a `dpconfig.properties` file in the current directory, with keys of the form `dp.<name>`;
2. a Java system property `-Ddp.<name>=...`;
3. an environment variable (the names below).

Booleans accept `1/true/yes/on` and `0/false/no/off`. An unset or unparseable value falls back to the default.

### General

| Environment variable | System property | Default | Purpose |
|---|---|---|---|
| `DP_REGISTRY` | `dp.registry` | `build/daikonpp_registry.jsonl` | Registry of all accepted candidates. |
| `DP_OUTCOMES` | `dp.outcomes` | `build/daikonpp_outcomes.jsonl` | Per-candidate outcome (verdict) file. |
| `DP_REGISTRY_RESET` | `dp.registryReset` | `true` | Delete the registry at the start of the run. |
| `DP_WORKDIR` | `dp.workDir` | `<java.io.tmpdir>/daikonpp_work` | Where working copies are created. |
| `DP_KEEP_WORK` | `dp.keepWork` | `true` | Keep the working copy (and shm directory) after the run. |
| `DP_THREADS` | `dp.threads` | number of CPUs (min 2) | Parallel LLM requests. |
| `DP_DEBUG` | `dp.debug` | `false` | Verbose logs (prompts sent, dropped expressions, …). |
| `DP_SCAN_INCLUDES` | `dp.scanIncludes` | (all) | Comma-separated path or package fragments; only matching files get program points. |

### LLM and prompts

| Environment variable | System property | Default | Purpose |
|---|---|---|---|
| `OPENAI_API_KEY` | — | — | Required for real OpenAI calls. |
| `DP_OPENAI_MODEL` | `dp.openaiModel` | `gpt-4.1` | One of `gpt-4.1`, `gpt-4.1-mini`, `gpt-4o`, `gpt-4o-mini`, `gpt-5`. Any other value falls back to `gpt-4.1-mini`. |
| `DP_PROMPT_STRATEGY` | `dp.promptStrategy` | `baseline` | `baseline`, `naive`, `fewshot`, `cot`, `stepwise`, `self_refine`, `multi_sample`. |
| `DP_CONTEXTS` | `dp.contexts` | all | Comma-separated context sections included in prompts: `METHOD_BODY`, `SCOPE`, `METHOD_JAVADOC`, `CLASS_DOC`, `TYPE_DOC`, `CALL_SITE`, `IO_EXAMPLES`, `CALLEE_DOC`. |
| `DP_CALL_SITES_INDEX` | `dp.callSitesIndex` | — | Index file for `CALL_SITE` context. |
| `DP_IO_EXAMPLES_INDEX` | `dp.ioExamplesIndex` | — | Index file for `IO_EXAMPLES` context. |
| `DP_LLM_CASSETTES` | `dp.llmCassettes` | — | Cassette directory of recorded LLM responses (see [LLM backends](#llm-backends)). |
| `DP_DISABLE_REAL_LLM` | `dp.disableRealLlm` | `false` | With cassettes: replay only, never call the LLM. |
| `DP_LLM_TOTAL_TIMEOUT_SEC` | `dp.llmTotalTimeoutSec` | `180` | Deadline for the **whole** LLM phase. Points not answered by then get no candidates. Raise it for large projects. |
| `DP_LLM_POLL_STEP_MS` | `dp.llmPollStepMs` | `1500` | Progress-report interval during the LLM phase. |
| `DP_LLM_DRY_RUN` | `dp.llmDryRun` | `false` | Build and count prompts only (see [Dry run](#dry-run-token-and-cost-estimate)). |
| `DP_LLM_PRICE_INPUT_PER_M` | `dp.llmPriceInputPerM` | — | USD per 1M input tokens, for the dry-run cost estimate. |
| `DP_LLM_PRICE_OUTPUT_PER_M` | `dp.llmPriceOutputPerM` | — | USD per 1M output tokens, for the dry-run cost estimate. |

`DP_INCLUDE_BODY` and `DP_BODY_MAX_CHARS` are still read and printed in the config summary, but they currently have **no effect**. Whether the method body is sent is controlled by `METHOD_BODY` in `DP_CONTEXTS`.

### Candidate filtering

| Environment variable | System property | Default | Purpose |
|---|---|---|---|
| `DP_NO_QUALITY_FILTER` | `dp.noQualityFilter` | `false` | Disable the whole static quality filter. |
| `DP_QUALITY_FILTER_<RULE>` | `dp.qualityFilter<Rule>` | `true` | Turn off a single rule (see below). |

Rules (`<RULE>` / `<Rule>`):

| Rule | Rejects |
|---|---|
| `MAX_LENGTH` / `MaxLength` | expressions longer than 200 characters |
| `ALWAYS_TRUE_LITERAL` / `AlwaysTrueLiteral` | the literal `true` |
| `TAUTOLOGY_NOT_X_OR_X` / `TautologyNotXOrX` | `!(e) \|\| (e)` |
| `FULL_RANGE_COMPARISON` / `FullRangeComparison` | e.g. `x >= Integer.MIN_VALUE` |
| `SELF_COMPARISON` / `SelfComparison` | e.g. `x == x` |
| `TRIVIAL_DISJUNCTION` / `TrivialDisjunction` | trivially true disjunctions such as `x == 1 \|\| x != 1 && true` |
| `NO_STATEMENTS` / `NoStatements` | expressions containing `;`, `{` or `}` |
| `NO_ASSIGNMENT` / `NoAssignment` | assignments |
| `FORBIDDEN_CONSTRUCTS` / `ForbiddenConstructs` | streams, `Optional`, lambdas (`->`), method references (`::`), `new `, regex `Pattern`/`Matcher`, `System.exit`, … |
| `REQUIRE_IN_SCOPE_NAME` / `RequireInScopeName` | expressions that mention no in-scope variable |
| `REQUIRE_RESULT_AT_EXIT` / `RequireResultAtExit` | non-void exit expressions that don't mention `result` |
| `PRIMITIVE_NULL_COMPARISON` / `PrimitiveNullComparison` | comparing a primitive with `null` |
| `UNKNOWN_IDENTIFIER` / `UnknownIdentifier` | identifiers that are not in scope |
| `PARSE_CHECK` / `ParseCheck` | expressions that don't parse as Java |

### Compilation and execution

| Environment variable | System property | Default | Purpose |
|---|---|---|---|
| `DP_AUTOFILTER_MAX_MODIFY_PASSES` | `dp.autofilterMaxModifyPasses` | `10` | Compile passes that remove the guard at each error location. |
| `DP_AUTOFILTER_MAX_EXTRA_PASSES` | `dp.autofilterMaxExtraPasses` | `20` | Further passes that restore each still-failing **file** to its original, uninstrumented version (losing all of that file's candidates). |
| `DP_EXTERNAL_COMPILE_CP` | `dp.externalCompileClasspath` | `""` | External mode: classpath for the auto-filter `javac` compile. |
| `DP_COMPILE_MAIN_SCRIPT` | `dp.compileMainScript` | — | Script used instead of `javac` to compile main sources during auto-filtering. |
| `DP_COMPILE_TEST_SCRIPT` | `dp.compileTestScript` | — | Same, for test sources (split mode). |
| `DP_STALE_CHECK_MINUTES` | `dp.staleCheckMinutes` | `1` | External mode: how long without progress before a run counts as hung (doubled on retries, up to 10). |
| `DP_MAX_TIMEOUT_MINUTES` | `dp.maxTimeoutMinutes` | `480` | External mode: upper bound for the per-run timeout (starts at 60 min, doubled on retries). |
| `DP_TEST_FILTER` | `dp.testFilter` | `false` | External mode: find and disable candidates that make tests fail (delta debugging). |
| `DP_TEST_FILTER_METHOD_BATCH_SIZE` | `dp.testFilterMethodBatchSize` | `1` | Currently unused by the delta-debugging test filter. |
| `DP_SHM_BASE` | — | `/dev/shm` | External mode: base directory for shared-memory results. |

---

## LLM backends

- **OpenAI (default).** Needs `OPENAI_API_KEY`.
- **Record and replay.** Set `DP_LLM_CASSETTES=<dir>`.
  Each prompt is looked up by a hash of its exact system and user text. A recorded response is replayed; otherwise the LLM is called and the response is recorded.
  Add `DP_DISABLE_REAL_LLM=1` to replay only: then no API key is needed, and points without a recorded response get no candidates.
  Any change to a prompt (strategy, contexts, `maxK`, source text) changes its key.
- **Local model.** `DP_LLM_PROVIDER=local`, `DP_LLM_LOCAL_MODEL` (default `qwen2.5:7b`), `DP_LLM_LOCAL_URL` (default `http://localhost:11434`).
  Requests go to an Ollama-compatible `<url>/api/generate` endpoint, with `DP_LLM_REQ_TIMEOUT_SEC` (default 45) per request.
  `DP_LLM_LOCAL_BACKEND` is accepted but does not currently change the protocol.

### Dry run (token and cost estimate)

With `DP_LLM_DRY_RUN=true`, Daikon++ scans the program points and builds every prompt exactly as a normal run would, but **sends nothing**:

- No API key is needed.
- No working copy is created, and your sources are only read.
- The registry is not reset.
- The run stops before injection.

It prints a summary:

- number of prompts, overall and per ENTRY/EXIT;
- input tokens: total, mean, median, p90, p99, max;
- the largest prompts.

Tokens are counted with the OpenAI tokenizer of `DP_OPENAI_MODEL`. It also writes one line per prompt to `daikonpp_dry_run_tokens.tsv`, next to the outcomes file.

With `DP_LLM_CASSETTES` set, it also reports:

- which prompts are already recorded;
- **output tokens** taken from the recorded responses (unrecorded prompts are estimated at the recorded mean).

With `DP_LLM_PRICE_INPUT_PER_M` and `DP_LLM_PRICE_OUTPUT_PER_M` set, it also prints the cost in USD.

The JSON schema sent with each request (structured output) is not counted.

---

## What the Tool Does (Pipeline)

1. **Scan** the main sources with JavaParser.
   Every method with a body gets two program points: **METHOD_ENTRY** and **METHOD_EXIT**. For each point, the in-scope variables and the selected context sections are extracted.
2. **Propose** invariants. One LLM request is made per program point, in parallel, built by the selected prompt strategy.
3. **Filter and deduplicate.** Unparseable expressions and those rejected by the quality filter are dropped, along with duplicates and anything beyond `maxK`. Accepted candidates are appended to the registry.
4. **Inject** guards into the working copy:
   - ENTRY guards go at the start of the method.
   - EXIT guards go before every `return`, with `result` bound to the returned value. `void` methods also get guards at the end of the body.
   - Exceptional exits (`throw`) are not checked.
   - A candidate whose guard cannot be generated is skipped (`FAILED_TO_INJECT`); the rest of the file is still instrumented.
   - A helper class `daikonpp/DpRuntime.java` is written into the instrumented source root.
5. **Compile with auto-filtering.** The code is recompiled until it builds or the pass budget runs out; candidates removed along the way are `FAILED_TO_COMPILE`.
   - Each compiler error is mapped back to the guard at its location, and that guard is removed.
   - In later passes, a file that still fails is restored to its original version.
   - In external mode, a build failure without `javac`-style errors (e.g. a build step that *runs* instrumented code) cannot be attributed to a candidate and aborts the run.
6. **Run** your main class, or in external mode your runner script.
   - A guard is evaluated each time it is reached, until it is falsified for the first time.
   - A guard whose expression evaluates to `false`, **or throws**, is recorded as falsified.
   - Exceptions are caught inside the guard, so a throwing invariant does not crash the program. An expression with side effects (e.g. one that calls a mutating method) can still change the program's behavior.
7. **Report.** Each candidate gets a verdict:

   | Verdict | Meaning |
   |---|---|
   | `HELD` | executed, never falsified |
   | `FALSIFIED` | executed and falsified |
   | `NEVER_EXECUTED` | compiled but never reached |
   | `FAILED_TO_COMPILE` | removed by auto-filtering |
   | `FAILED_TO_INJECT` | guard could not be generated |

---

## Outputs

- `daikonpp_registry.jsonl` (`DP_REGISTRY`) — one JSON line per accepted candidate: `id`, `expr`, `rationale`, `kind`, `element`, `file`, `createdAt`.
- `daikonpp_outcomes.jsonl` (`DP_OUTCOMES`) — one line per candidate: `id`, `compiled`, `executed`, `verdict`.
- `daikonpp_invariant_metrics.jsonl` (next to the outcomes file) — variable counts and project-method calls of each held invariant.
- `<DP_WORKDIR>/project-<timestamp>/` — the instrumented working copy (kept unless `DP_KEEP_WORK=false`).
  - Native modes:
    - `daikonpp-classes/` — compiled classes, plus the `dp_sources.txt` given to `javac` and the `dp-javac.out`/`.err` logs;
    - `daikonpp-run.log` — your program's stdout and stderr, interleaved with the `INV_FAIL` JSON lines of falsified invariants.
  - External mode:
    - `.daikonpp-classes/` — the auto-filter compile output;
    - `daikonpp-run.log` — the script's output; earlier runs, if the script was rerun, are archived as `daikonpp-run-<n>.log`;
    - `.daikonpp-disabled-invariants.txt` — guards disabled because they hung.

The console shows:

- the config summary;
- the number of program points;
- LLM progress and the filter breakdown (how many candidates were dropped by each step);
- injection, compilation and run summaries;
- the results.

The results section looks like:

    >>> Totals: all=… compiled=… non-compiled=… failed-to-inject=… executed=… falsified=… observed-held=… never-executed=… (…)
    >>> OBSERVED-HELD invariants by method (ENTRY & EXIT):
      - pkg.Class#m(args):ret
          [METHOD_ENTRY] <uuid> :: <expr>   (varCount1=…, …)
    >>> FALSIFIED invariants by method (ENTRY & EXIT):
    >>> FAILED-TO-COMPILE invariants by method:
    >>> NEVER-EXECUTED invariants by method (compiled but never observed):
    >>> Registry: /abs/path/to/daikonpp_registry.jsonl
    >>> Outcomes: /abs/path/to/daikonpp_outcomes.jsonl
    >>> Run log: /abs/path/to/daikonpp-run.log

---

# E2E Test Suite (Record → Replay)

The end-to-end tests check that Daikon++ produces stable results by comparing pipeline outputs against versioned snapshots.

The workflow:
1. **Record** snapshots with real LLM calls (writes `expected/` and cassettes).
2. **Replay** them offline in tests (no network).

---

## Directory structure

```
src/test/resources/daikonpp-pipeline/<case>/
  ├─ input/              # Java sources for the case (and run.sh for external cases)
  ├─ expected/           # registry.jsonl, outcomes.jsonl
  └─ config.json         # optional, see below

src/test/cassettes/      # recorded LLM responses for offline replay
```

`config.json` keys:

| Key | Default | Meaning |
|---|---|---|
| `mainClass` | `com.example.Main` | main class (native mode) |
| `maxK` | `5` | max invariants per program point |
| `execMode` | — | `"external"` runs the case in external-project mode |
| `command` | — | runner command; its last element is the runner script (e.g. `["bash", "run.sh"]`) |
| `dp` | — | object of settings, each applied as `-Ddp.<key>=<value>`; arrays are joined with commas (e.g. `{"contexts": ["METHOD_BODY","SCOPE"], "promptStrategy": "fewshot"}`) |

Other keys are ignored.

---

## Running tests (offline replay)

```bash
./gradlew test        # all tests
./gradlew e2e         # only the E2E suite
```

Gradle sets, for both tasks:
- `DP_DISABLE_REAL_LLM=1`
- `DP_LLM_CASSETTES=src/test/cassettes` (unless `DP_LLM_CASSETTES` is already set in your environment)

`PipelineE2ETest` can be limited to some cases with the system property `dp.cases=caseA,caseB`. Gradle does **not** currently forward `-Ddp.cases=...` from the command line to the test JVM, so `./gradlew e2e -Ddp.cases=...` still runs every case.

---

## Recording snapshots (real LLM calls)

The provided scripts rebuild the jar (without tests), enable real LLM calls, rebuild the expected outputs and add new cassettes:

```bash
# Record a single case
./scripts/record_one.sh 00-baseline

# Record all cases that have input/
./scripts/record_all.sh
```

They need `OPENAI_API_KEY`.

**Limitation:** the scripts read only `mainClass` and `maxK` from `config.json`, and always run in native mode. Settings under `dp` and `execMode`/`command` are **not** applied. Cases that use them (e.g. `00-baseline`, `02-external-command`, `05-context-test`) would be recorded with different settings than the test replays with.

Outputs:
- `expected/registry.jsonl` and `expected/outcomes.jsonl`
- new cassette JSON files under `src/test/cassettes/`

Commit both after verifying the results.

---

## Adding a new test case

1. Create a folder:
   ```
   src/test/resources/daikonpp-pipeline/06-new-case/
     ├─ input/          # Java files
     └─ config.json     # optional
   ```
2. Record it, then replay the E2E suite:
   ```bash
   ./scripts/record_one.sh 06-new-case
   ./gradlew e2e
   ```
3. Commit the updated `expected/` and cassette files.

---

## Troubleshooting

- **`DP_LLM_CASSETTES dir missing or unreadable`:** create it with `mkdir -p src/test/cassettes`.
- **Many points without candidates in a large project:** the LLM phase hit `DP_LLM_TOTAL_TIMEOUT_SEC` (default 180 s); raise it.
- **Empty outcomes in a test:** make sure the run writes to the expected `dp.outcomes` path.
- **Unexpected E2E diffs after prompt or code changes:** prompts changed, so their cassette keys changed. Re-record the affected cases and commit the updated snapshots.
