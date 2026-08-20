# Daikon++

Daikon++ instruments Java programs to check candidate invariants at **method entry** and **method exit**. For each program point, it asks an LLM for likely boolean expressions (structured output), filters and deduplicates them, injects runtime guards into a **working copy** of your sources, compiles and runs that copy, then reports the invariants that **held** (not falsified at runtime). Original sources are never modified.

---

## Prerequisites

- JDK 17+ on PATH (`javac` and `java`)
- OpenAI API key in `OPENAI_API_KEY`  
  (If you prefer `DP_OPENAI_API_KEY`, just map it once: `export OPENAI_API_KEY="$DP_OPENAI_API_KEY"`.)
- Internet access from the machine that runs Daikon++

---

## Setup

Make all shell scripts executable:

```bash
find . -type f -name "*.sh" -exec chmod +x {} +
```
---

## Build the fat JAR

    ./gradlew clean shadowJar
    # → builds: build/libs/daikonplusplus.jar

---

## Run the fat JAR

    export OPENAI_API_KEY=sk-...your-key...

    # CLI:
    # java -jar build/libs/daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]

    # Notes:
    # - <classpath>: use ":" on macOS/Linux, ";" on Windows. Use "." if no external deps.
    # - <mainClass>: fully qualified (e.g., com.example.Main). Use "Main" for default package.

    # Example:
    java -jar build/libs/daikonplusplus.jar /path/to/project/src . com.example.Main 5 -- foo bar

---

## CLI

    java -jar build/libs/daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]

- `<srcRoot>` — path to source tree to instrument (e.g., `/project/src`)
- `<classpath>` — classpath to compile & run your program (use `:` on macOS/Linux, `;` on Windows). Do **not** include Daikon++ itself.
- `<mainClass>` — fully qualified main class (e.g., `com.example.Main`; for default package just `Main`)
- `[maxK]` — max invariants per program point (default **5**)
- `-- program args...` — everything after `--` is passed to your program unchanged

**Example:**

    java -jar build/libs/daikonplusplus.jar /path/to/app/src . com.example.Main 5 -- foo bar

---

# Execution Modes

Daikon++ supports two modes depending on your project.

## Native Mode (default)

Use this when:
- You have plain Java source files
- You can compile with `javac`

Behavior:
- Daikon++ compiles using `javac`
- Runs using `java`
- No external scripts required

## External Project Configuration (Compile + Test Runner)

In **external-project mode**, Daikon++ does not compile or run your project itself.  
Instead, **you provide scripts** that define how to compile and how to run tests.

There are **two ways** to provide these:

---

### 1. Test Runner (Required)

You must provide a **test runner script** via CLI:

```
--runner-script <path-to-script>
```

This script is executed by Daikon++ to run your test suite.

#### Requirements

- Must be **executable**
- Must **exit with code 0 on success**, non-zero on failure
- Runs from the **project root**
- Should run the full test suite (or the relevant subset)

#### Example

```bash
#!/usr/bin/env bash
set -e

mvn -q \
  -pl hudi-client/hudi-java-client \
  -DskipITs \
  -Duser.timezone=UTC \
  test
```

---

### 2. Compile Script (Optional but Recommended)

You can provide compile scripts via environment variables:

```
DP_COMPILE_MAIN_SCRIPT
DP_COMPILE_TEST_SCRIPT
```

If provided, Daikon++ will use them for **auto-filter compilation** instead of `javac`.

#### Requirements

- Must be **executable**
- Must compile the project
- Must **fail (non-zero exit)** if compilation fails
- Must use `$DP_PROJECT_ROOT` as working directory

#### Example

```bash
#!/usr/bin/env bash
set -euo pipefail

cd "$DP_PROJECT_ROOT"

mvn \
  -pl hudi-client/hudi-java-client \
  -am \
  -DskipTests \
  compile
```

---

### 3. Required Environment Variables

#### Compile classpath (required)

```
DP_EXTERNAL_COMPILE_CP
```

This is used internally by Daikon++ during filtering.

Example:

```
export DP_EXTERNAL_COMPILE_CP=/path/to/module/target/classes
```

---

### 4. How Everything Connects

When you run:

```
java -jar daikonplusplus.jar \
  --external-project \
  --project-root <projectRoot> \
  --main-src <mainSrc> \
  --test-src <testSrc> \
  --runner-script <runner.sh>
```

Daikon++ will:

1. Copy the project to a working directory
2. Inject invariants into source files
3. Run **compile auto-filter**:
    - Uses `DP_COMPILE_*_SCRIPT` if provided
    - Otherwise falls back to internal compilation
4. Run the **test runner script**
5. Collect invariant execution + failures
6. (Optional) Run **test-based filtering** using the same runner script

---

### 5. Important Notes

- The **runner script is used multiple times**:
    - Initial execution
    - Each test-filter attempt
    - Final validation

- The **compile script is only used during auto-filtering**, not during test runs

- Daikon++ injects these environment variables when running scripts:
    - `DP_PROJECT_ROOT`
    - `DP_RUN_LOG`
    - `DP_INV_DIR`

---

### Summary

| Component         | How user provides it          | Required |
|------------------|------------------------------|----------|
| Test runner      | `--runner-script`            | Yes      |
| Compile script   | `DP_COMPILE_MAIN_SCRIPT`     | No       |
| Compile classpath| `DP_EXTERNAL_COMPILE_CP`     | Yes      |
---

## Running with Gradle

    export OPENAI_API_KEY=sk-...your-key...
    ./gradlew run --args="<srcRoot> <classpath> <mainClass> [maxK] [-- program args...]"

**Example:**

    ./gradlew run --args="/Users/me/demo/src . Main 5"

---

## Configuration (Environment Variables)

Configuration precedence:

1. `dpconfig.properties`
2. JVM flags (`-Ddp.xxx`)
3. Environment variables (`DP_XXX`)
4. Defaults

---

### Core

| Variable | Default |
|----------|--------|
| `DP_THREADS` | available processors |
| `DP_REGISTRY` | build/daikonpp_registry.jsonl |
| `DP_OUTCOMES` | build/daikonpp_outcomes.jsonl |

---

### Feature flags

| Variable | Default |
|----------|--------|
| `DP_INCLUDE_BODY` | 1 |
| `DP_KEEP_WORK` | 1 |
| `DP_DEBUG` | 0 |
| `DP_REGISTRY_RESET` | 1 |
| `DP_NO_QUALITY_FILTER` | 0 |

---

### LLM

| Variable | Default |
|----------|--------|
| `DP_LLM_PROVIDER` | openai |
| `DP_OPENAI_MODEL` | gpt-4.1 |
| `DP_LLM_CASSETTES` | (unset) |
| `DP_DISABLE_REAL_LLM` | 0 |

---

### Local LLM

| Variable | Default |
|----------|--------|
| `DP_LLM_LOCAL_BACKEND` | ollama |
| `DP_LLM_LOCAL_URL` | http://localhost:11434 |
| `DP_LLM_LOCAL_MODEL` | qwen2.5:7b |

---

### Timeouts

| Variable | Default |
|----------|--------|
| `DP_LLM_TOTAL_TIMEOUT_SEC` | 180 |
| `DP_LLM_REQ_TIMEOUT_SEC` | 45 |
| `DP_LLM_POLL_STEP_MS` | 1500 |

---

### Prompting

| Variable | Default |
|----------|--------|
| `DP_PROMPT_STRATEGY` | baseline |

Options:

- baseline
- naive
- fewshot
- cot
- stepwise
- self_refine
- multi_sample

---

### Context control

| Variable | Default |
|----------|--------|
| `DP_CONTEXTS` | all enabled |

Values:

- METHOD_BODY
- SCOPE
- METHOD_JAVADOC
- CLASS_DOC
- TYPE_DOC
- CALL_SITE
- IO_EXAMPLES
- CALLEE_DOC

---

### External mode

| Variable | Description |
|----------|------------|
| `DP_COMPILE_MAIN_SCRIPT` | compile script |
| `DP_COMPILE_TEST_SCRIPT` | test compile script |
| `DP_EXTERNAL_COMPILE_CP` | classpath |

---

### Test filtering

| Variable | Default |
|----------|--------|
| `DP_TEST_FILTER` | 0 |
| `DP_TEST_FILTER_METHOD_BATCH_SIZE` | 1 (initial `ddmin` chunk count, clamped to ≥2) |

---

### Scan filtering

| Variable | Description |
|----------|------------|
| `DP_SCAN_INCLUDES` | whitelist packages |

---

## What the Tool Does (Pipeline)

1. Scan sources → extract program points (**METHOD_ENTRY**, **METHOD_EXIT**)
2. LLM proposes invariants
3. Deterministic filtering (syntax + heuristics)
4. Inject into working copy
5. Compile (auto-filter removes non-compilable invariants)
6. Execute program / tests
7. Parse logs → classify invariants:
    - HELD
    - FALSIFIED
    - NEVER_EXECUTED
    - FAILED_TO_COMPILE
8. **(External mode only, optional)** Run test-based filtering to remove invariants that cause test failures

---
## Test-Based Filtering (Post-Execution)

When enabled (`DP_TEST_FILTER=1` in external mode), Daikon++ performs an additional refinement step to identify invariants that cause test failures.

### Algorithm

Daikon++ isolates the culprit invariants using [delta debugging](https://www.debuggingbook.org/html/DeltaDebugger.html) (`ddmin`):

1. Take a snapshot of the injected project
2. Extract **executed invariant IDs** from the initial run, and the recognized failure signature (if any) from its log
3. If no failure was recognized, or no executed invariant has a matching source block, stop — nothing to isolate
4. **Sanity trial**: disable every candidate invariant and re-run; if the failure still reproduces, it isn't caused by any injected invariant (e.g. a build/lint failure) — stop, not attributable
5. **`ddmin` search**: starting from the full candidate set, repeatedly test shrinking subsets and their complements against fresh copies of the project:
    - Split the current candidate set into `n` chunks (`n` starts at `DP_TEST_FILTER_METHOD_BATCH_SIZE`, clamped to ≥2)
    - Try each chunk alone (all other candidates disabled); if the *same* failure reproduces, narrow to that chunk and restart at `n = 2`
    - Otherwise try each chunk's complement (that chunk disabled, the rest enabled); if the *same* failure reproduces, narrow to that complement
    - If neither narrows the set, double `n` and retry
    - Stop once `n` reaches the candidate set's size with no further narrowing — the set is 1-minimal
    - A trial only counts as reproducing the failure if the run fails **and** the first recognized failure in its log matches the original signature — an unrelated/flaky failure elsewhere in the suite is not mistaken for evidence

6. Produce the final result by disabling exactly the isolated 1-minimal culprit set.

---

## Outputs

Under the Daikon++ project’s `build/` directory:

- `daikonpp_work/src-<timestamp>/` — working copy with instrumented sources
    - `daikonpp-classes/` — compiled classes
    - `daikonpp-classes/dp_sources.txt` — source list fed to `javac`
    - `daikonpp-run.log` — JSON lines of **falsified** invariants at runtime
    - `daikonpp-run.err` — your program’s stderr (if any)
- `daikonpp_registry.jsonl` — append-only registry of **all accepted** invariants (UUID, phase, element, expr, file, timestamp)

Console output includes the scan summary, LLM progress, injection/compile/run summaries, and a final section like:

    >>> HELD invariants by method (ENTRY & EXIT):
      - pkg.Class#m(args):ret
          [METHOD_ENTRY] <uuid> :: <expr>
          [METHOD_EXIT ] <uuid> :: <expr>
    >>> Registry: /abs/path/to/daikonpp_registry.jsonl
    >>> Run log: /abs/path/to/daikonpp-run.log

---

# E2E Test Suite (Record → Replay)

The end-to-end tests verify that Daikon++ produces stable results by comparing pipeline outputs
against versioned snapshots.

The workflow:
1. **Record** snapshots with real LLM calls (writes `expected/` + cassettes)
2. **Replay** offline in tests (no network)

---

## Directory structure

```
src/test/resources/daikonpp-pipeline/<case>/
  ├─ input/              # Java sources for the case
  ├─ expected/           # registry.jsonl, outcomes.jsonl
  └─ config.json         # optional: {"mainClass":"…","maxK":5}

src/test/cassettes/      # replay data for offline tests
```

---

## Running tests (offline replay)

Simply run:

```bash
./gradlew test        # all tests
./gradlew e2e         # only E2E suite
./gradlew e2e -Ddp.cases=00-baseline,01-regression
```

Gradle automatically sets:
- `DP_DISABLE_REAL_LLM=1`
- `DP_LLM_CASSETTES=src/test/cassettes`

No manual flag management required.

---

## Recording snapshots (real LLM calls)

Use the provided scripts. They **temporarily enable real LLM calls**, rebuild the expected outputs,
and update the cassettes.

```bash
# Record a single case
./scripts/record_one.sh 00-baseline

# Record all cases that have input/
./scripts/record_all.sh
```

Outputs:
- `expected/registry.jsonl` and `expected/outcomes.jsonl`
- Updated cassette JSON files under `src/test/cassettes/`

Commit both directories after verifying the results.

---

## Adding a new test case

1. Create a folder:
   ```
   src/test/resources/daikonpp-pipeline/02-new-case/
     ├─ input/          # Java files
     └─ config.json     # optional
   ```
2. Run:
   ```bash
   ./scripts/record_one.sh 02-new-case
   ./gradlew e2e -Ddp.cases=02-new-case
   ```
3. Commit updated `expected/` and cassette files.

---

## Troubleshooting

- **Missing cassette directory:**  
  `mkdir -p src/test/cassettes`

- **Empty outcomes:**  
  Make sure the app writes to the correct `-Ddp.outcomes` path.

- **Unexpected diffs after prompt/code changes:**  
  Re-record affected cases and commit updated snapshots.
