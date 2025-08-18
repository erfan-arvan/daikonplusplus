# Daikon++

Daikon++ instruments Java programs to check candidate invariants at **method entry** and **method exit**. For each program point, it asks an LLM for likely boolean expressions (structured output), filters and deduplicates them, injects runtime guards into a **working copy** of your sources, compiles and runs that copy, then reports the invariants that **held** (not falsified at runtime).

---

## Prerequisites

- **JDK 17+** on PATH (`javac` and `java`)
- **OpenAI API key** in `DP_OPENAI_API_KEY`
- Internet access from the machine that runs Daikon++

---

# Build the fat JAR
./gradlew clean shadowJar
# → builds: build/libs/daikonplusplus.jar

# Run it (set your API key first)
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

The fat-jar CLI is:

    java -jar daikonplusplus.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]

- `<srcRoot>` — path to **source** tree to instrument (e.g., `/project/src`)
- `<classpath>` — classpath to **compile & run** your program (use `:` on macOS/Linux, `;` on Windows). Do **not** include Daikon++ itself.
- `<mainClass>` — fully qualified main class (e.g., `com.example.Main`; for default package just `Main`)
- `[maxK]` — max invariants per program point (default **5**)
- `-- program args...` — everything after `--` is passed to your program unchanged

**Example:**

    java -jar daikonplusplus.jar /path/to/app/src . com.example.Main 5 -- foo bar

---

## Running with Gradle (recommended during development)

Set required/optional environment variables, then run:

    export DP_OPENAI_API_KEY=sk-...your-key...
    export DP_INCLUDE_BODY=1       # optional: send full method bodies to LLM as context (off by default)
    export DP_KEEP_WORK=1          # optional: keep the working copy for inspection
    export DP_DEBUG=1              # optional: verbose logging
    # export DP_LLM_DUMP=1         # optional: dump per-point prompts & raw LLM items to files

    ./gradlew run --args="<srcRoot> <classpath> <mainClass> [maxK] [-- program args...]"

**Example:**

    ./gradlew run --args="/path/to/app/src . com.example.Main 5 -- arg1 arg2"

---

## Running a Fat JAR

If your build produces a standalone JAR (e.g., `build/libs/daikonplusplus-all.jar`):

    export DP_OPENAI_API_KEY=sk-...your-key...
    java -jar build/libs/daikonplusplus-all.jar <srcRoot> <classpath> <mainClass> [maxK] [-- program args...]

---

## Configuration (Environment Variables)

> By default, the LLM **does not** receive method bodies. Set `DP_INCLUDE_BODY=1` to include the **entire** method body (comments included) as *context only*.

| Variable | Default | Purpose |
|---|---:|---|
| `DP_OPENAI_API_KEY` | — | **Required.** OpenAI API key. |
| `DP_OPENAI_MODEL` | gpt-4.1-mini (example) | Model used for invariant generation. |
| `DP_INCLUDE_BODY` | `1` | `1` to send **full method bodies** to the LLM as context. |
| `DP_THREADS` | (CPU cores) | Max CPU threads for scanning/injection. |
| `DP_LLM_MAX_CONCURRENCY` | 4–8 | Caps concurrent LLM calls to avoid throttling. |
| `DP_LLM_REQ_TIMEOUT_SEC` | 45 | Per-request timeout for a single program point. |
| `DP_LLM_TOTAL_TIMEOUT_SEC` | 180 | Global deadline for the entire LLM phase; stragglers are skipped. |
| `DP_DEBUG` | `0` | `1` for verbose logs (scopes, filtered expressions, counts). |
| `DP_LLM_DUMP` | `0` | `1` to write per-point JSON dumps (prompt, scope, raw, kept). |
| `DP_KEEP_WORK` | `0` | `1` to keep the working copy folder with instrumented sources. |
| `DP_REGISTRY_RESET` | `0` | `1` to clear the registry at the start of the run. |

---

## What the Tool Does (Pipeline)

1. **Scan sources** (JavaParser) → discover program points (ENTRY & EXIT) and the in-scope names/types. Optional: capture full method body as context when `DP_INCLUDE_BODY=1`.
2. **Propose invariants** (LLM, structured) per point in parallel using distinct ENTRY/EXIT prompts.
3. **Filter & deduplicate** deterministically (drop tautologies, streams/lambdas/method refs, invented helpers, unknown identifiers, EXIT-without-`result`, null-checks on primitives, etc).
4. **Inject guards** into a **working copy** of sources (original code untouched). EXIT checks run **before every return**; `void` methods get a tail block.
5. **Compile & run** the instrumented copy (javac/java with your classpath).
6. **Parse logs** of falsifications and **report held** invariants by method and phase.

---

## Outputs

**Files & directories** (under the Daikon++ project `build/`):

- `daikonpp_work/src-<timestamp>/` — **working copy** with instrumented sources  
  - `daikonpp-classes/` — compiled classes  
  - `daikonpp-classes/dp_sources.txt` — source list fed to `javac`  
  - `daikonpp-run.log` — JSON lines of **falsified** invariants at runtime  
  - `daikonpp-run.err` — your program’s stderr (if any)
- `daikonpp_registry.jsonl` — append-only registry of **all accepted** invariants (UUID, phase, element, expr, file, timestamp)
- `daikonpp_llm/` (if `DP_LLM_DUMP=1`) — per-program-point JSON: prompt, scope, raw items, kept items

**Console output** includes:
- Scan summary (ENTRY/EXIT counts)
- LLM progress and totals (per kind), with timeout messages if any
- Injection summary (files updated, ENTRY/EXIT counts)
- Compile/run commands and any errors
- Final report of **held** invariants, e.g.:

    >>> HELD invariants by method (ENTRY & EXIT):
      - pkg.Class#m(args):ret
          [METHOD_ENTRY] <uuid> :: <expr>
          [METHOD_EXIT ] <uuid> :: <expr>
    >>> Registry: /abs/path/to/daikonpp_registry.jsonl
    >>> Run log: /abs/path/to/daikonpp-run.log

---

## Examples

**Minimal project (default package)**

    export DP_OPENAI_API_KEY=sk-...
    ./gradlew run --args="/Users/me/demo/src . Main 5"

**With full method bodies & dumps**

    export DP_OPENAI_API_KEY=sk-...
    export DP_INCLUDE_BODY=1
    export DP_LLM_DUMP=1
    export DP_KEEP_WORK=1
    ./gradlew run --args="/Users/me/demo/src libs/*:out com.example.Main 5 -- foo bar"

---

## Troubleshooting

- **Long waits during LLM phase**  
  Tune concurrency/timeouts:

      export DP_LLM_MAX_CONCURRENCY=4
      export DP_LLM_REQ_TIMEOUT_SEC=60
      export DP_LLM_TOTAL_TIMEOUT_SEC=240

  Stragglers are cancelled at the global deadline; completed results are used.

- **Compilation errors after injection**  
  Inspect the working copy and enable debug/dumps:

      export DP_KEEP_WORK=1
      export DP_DEBUG=1
      export DP_LLM_DUMP=1

  Inspect files in `build/daikonpp_work/...`. You can also try `DP_INCLUDE_BODY=0` and reduce `[maxK]`.

- **Program fails to launch**  
  Check `<classpath>` and `<mainClass>`. If using packages, `mainClass` must be fully qualified.

- **Missing EXIT invariants**  
  Ensure methods have bodies and returns. EXIT prompts require `result` for non-void methods. Use `DP_DEBUG=1` and examine dumps in `daikonpp_llm/`.

---




