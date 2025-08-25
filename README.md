# Daikon++

Daikon++ instruments Java programs to check candidate invariants at **method entry** and **method exit**. For each program point, it asks an LLM for likely boolean expressions (structured output), filters and deduplicates them, injects runtime guards into a **working copy** of your sources, compiles and runs that copy, then reports the invariants that **held** (not falsified at runtime). Original sources are never modified.

---

## Prerequisites

- JDK 17+ on PATH (`javac` and `java`)
- OpenAI API key in `OPENAI_API_KEY`  
  (If you prefer `DP_OPENAI_API_KEY`, just map it once: `export OPENAI_API_KEY="$DP_OPENAI_API_KEY"`.)
- Internet access from the machine that runs Daikon++

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

## Running with Gradle

    export OPENAI_API_KEY=sk-...your-key...
    ./gradlew run --args="<srcRoot> <classpath> <mainClass> [maxK] [-- program args...]"

**Example:**

    ./gradlew run --args="/Users/me/demo/src . Main 5"

---

## Configuration (Environment Variables)

Defaults are chosen for a smooth out-of-the-box run. You can override any of these per run.

| Variable            | Default                         | Purpose |
|---------------------|---------------------------------|---------|
| `OPENAI_API_KEY`    | — (required)                    | OpenAI API key used by the client. |
| `DP_REGISTRY`       | `build/daikonpp_registry.jsonl` | Path to the append-only registry file. (System prop `-Ddp.registry=...` also supported.) |
| `DP_INCLUDE_BODY`   | `1` (on)                        | Send the **full method body** (incl. comments) to the LLM as context for invariant proposals. |
| `DP_KEEP_WORK`      | `1` (on)                        | Keep the **working copy** folder after the run for inspection. |
| `DP_DEBUG`          | `1` (on)                        | Verbose logs (scopes, decisions, counts). |
| `DP_REGISTRY_RESET` | `1` (on)                        | Clear the registry file at the start of the run. |

Set to `0`/`false`/`off` to disable (e.g., `DP_DEBUG=0`).

---

## What the Tool Does (Pipeline)

1. Scan sources (JavaParser) → discover program points (**METHOD_ENTRY** & **METHOD_EXIT**) and in-scope names/types. If `DP_INCLUDE_BODY=1`, capture the **entire** method body as additional context for the LLM.
2. Propose invariants via OpenAI chat **structured** completions (ENTRY and EXIT prompts are distinct). Calls are issued **in parallel**.
3. Filter & deduplicate deterministically: drop tautologies/trivialities, expressions with unknown identifiers, EXIT expressions that rely on `result` where none exists, etc.
4. Inject guards into a **working copy** of the source tree. EXIT guards are inserted **before every return**; `void` methods get a tail block to cover fall-through. Exceptions from guard evaluation are caught and logged so the program keeps running.
5. Compile & run the working copy with `javac`/`java` using your classpath.
6. Parse logs of falsifications and report **held** invariants, grouped by method and phase (ENTRY/EXIT).

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