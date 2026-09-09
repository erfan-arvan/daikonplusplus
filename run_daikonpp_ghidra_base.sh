#!/usr/bin/env bash
set -uo pipefail   # same as the Hudi / libGDX scripts

########################################
# PATHS
########################################
SCRIPT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT="${ROOT:-$SCRIPT_DIR}"
GHIDRA_DIR="${GHIDRA_DIR:-$ROOT/ghidra}"
DPP_DIR="${DPP_DIR:-$ROOT/daikonplusplus}"

# "Base" (Ghidra/Features/Base) is Ghidra's flagship feature module: it's the
# largest module in the project (~3.5k main sources) and implements the core
# reverse-engineering functionality (CodeBrowser, built-in analyzers, etc.),
# and it ships its own substantial JUnit test suite (~200 test sources).
# Gradle project name is ":Base" (Ghidra derives project names from the
# containing directory name -- see gradle/support/settingsUtil.gradle).
MODULE="Ghidra/Features/Base"
GRADLE_PROJECT=":Base"
MAIN_SRC="$MODULE/src/main/java"
TEST_SRC="$MODULE/src/test/java"

########################################
# HPC scratch
########################################
ACCOUNT="${ACCOUNT:-mjk76}"
SCRATCH_ROOT="/scratch/${ACCOUNT}/${USER}"
SCRATCH_BASE="${SCRATCH_ROOT}/promptstudy"

mkdir -p "$SCRATCH_BASE"

export TMPDIR="${SCRATCH_BASE}/tmp"
export GRADLE_USER_HOME="${SCRATCH_BASE}/.gradle"
export DP_WORKDIR="${SCRATCH_BASE}/daikonpp_work"

mkdir -p "$TMPDIR" "$GRADLE_USER_HOME" "$DP_WORKDIR"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=${TMPDIR}"

echo "JAVA: $(which java)"

########################################
# LLM / Cassette config
########################################
export DP_LLM_CASSETTES="${DP_LLM_CASSETTES:-$DPP_DIR/src/test/cassettes}"

########################################
# Other configs
########################################
MAXK="${MAXK:-5}"

########################################
# Sanity checks
########################################
[[ -d "$GHIDRA_DIR" ]] || { echo "ERROR: Ghidra repo not found: $GHIDRA_DIR"; exit 1; }
[[ -x "$GHIDRA_DIR/gradlew" ]] || { echo "ERROR: Ghidra repo has no gradlew: $GHIDRA_DIR"; exit 1; }
[[ -x "$DPP_DIR/gradlew" ]] || { echo "ERROR: Daikon++ repo not found: $DPP_DIR"; exit 1; }

########################################
# Use pre-built jar
########################################
DAIKONPP_JAR="${DP_DAIKONPP_JAR:?ERROR: DP_DAIKONPP_JAR not set}"

########################################
# One-time Ghidra dependency prep marker
#
# Ghidra (application.properties: application.java.min=25) needs its
# non-Maven-Central dependencies fetched and dev environment prepped once
# per checkout before anything will compile. Since the compile/runner
# scripts below get invoked many times by Daikon++ during a run, this is
# gated behind a marker file so it only runs once.
########################################
export DP_GHIDRA_PREP_MARKER="$SCRATCH_BASE/.ghidra_prepped"

########################################
# External COMPILE script (IDENTICAL to laptop)
########################################
COMPILE_SCRIPT="$SCRATCH_BASE/ghidra_compile.sh"

cat > "$COMPILE_SCRIPT" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

# NOTE: adjust to whatever module name provides a JDK satisfying Ghidra's
# application.java.min (25 as of Ghidra 12.3, in Ghidra/application.properties)
module load Java/21

cd "$DP_PROJECT_ROOT"

if [[ ! -f "$DP_GHIDRA_PREP_MARKER" ]]; then
  ./gradlew -I gradle/support/fetchDependencies.gradle init --no-daemon
  ./gradlew prepdev --no-daemon
  touch "$DP_GHIDRA_PREP_MARKER"
fi

./gradlew :Base:compileJava --no-daemon
EOF

chmod +x "$COMPILE_SCRIPT"

export DP_COMPILE_MAIN_SCRIPT="$COMPILE_SCRIPT"
export DP_COMPILE_TEST_SCRIPT="$COMPILE_SCRIPT"

########################################
# External TEST runner (IDENTICAL to laptop)
########################################
RUNNER="$SCRATCH_BASE/ghidra_run_tests.sh"

cat > "$RUNNER" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

module load Java/21

cd "$DP_PROJECT_ROOT"

./gradlew :Base:test --no-daemon --info
EOF

chmod +x "$RUNNER"

export DP_EXTERNAL_CMD="$RUNNER"

########################################
# Minimal classpath (IDENTICAL to laptop)
########################################
export DP_EXTERNAL_COMPILE_CP="$GHIDRA_DIR/$MODULE/build/classes/java/main:$GHIDRA_DIR/$MODULE/build/resources/main"

# Call sites
export DP_CALL_SITES_INDEX="/project/mjk76/ea442/promptstudy/ghidra_callsites.json"

# I/O examples (few-shot examples rendered into the LLM prompt context)
export DP_IO_EXAMPLES_INDEX="/project/mjk76/ea442/promptstudy/ghidra-s5_io_examples.json"

########################################
# Enable test-failure-based invariant filtering
########################################
export DP_TEST_FILTER=true

########################################
# Run Daikon++ (IDENTICAL to laptop)
########################################
echo ">>> Running Daikon++"

cd "$GHIDRA_DIR"

CMD=(
  java -jar "$DAIKONPP_JAR"
  --external-project
  --project-root "$GHIDRA_DIR"
  --main-src "$MAIN_SRC"
  --test-src "$TEST_SRC"
  --runner-script "$RUNNER"
  "$MAXK"
)

echo ">>> CMD: ${CMD[*]}"
"${CMD[@]}"

echo ">>> Done."
