#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# record_one.sh — Record an E2E test snapshot for a single case.
#
# This script runs the Daikon++ pipeline on one test case (e.g., 00-baseline),
# allowing *real* LLM calls and saving both the expected outputs and LLM
# cassettes for deterministic replay in future tests.
#
# It performs the following steps:
#   1. Builds the fat JAR (skipping tests)
#   2. Runs the pipeline on the given input case
#   3. Writes registry.jsonl and outcomes.jsonl into:
#        src/test/resources/daikonpp-pipeline/<case>/expected/
#   4. Records any LLM calls into src/test/cassettes/
#
# Usage:
#   ./scripts/record_one.sh <case-name>
#
# Example:
#   ./scripts/record_one.sh 00-baseline
#
# To record *all* cases automatically, use:
#   ./scripts/record_all.sh
#
# Environment variables:
#   DP_LLM_CASSETTES   Directory for storing LLM interaction logs (set automatically)
#   DP_DISABLE_REAL_LLM If set to 1, disables real LLM calls (should be UNSET for recording)
#
# -----------------------------------------------------------------------------
########################################
# HARD RESET: force native execution
########################################
unset DP_COMPILE_MAIN_SCRIPT
unset DP_COMPILE_TEST_SCRIPT
unset DP_EXTERNAL_COMPILE_CP

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <case-name>   (e.g., 00-baseline)"
  exit 1
fi

CASE="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CASEDIR="$ROOT/src/test/resources/daikonpp-pipeline/$CASE"
INPUT="$CASEDIR/input"
EXPECTED="$CASEDIR/expected"

if [[ ! -d "$INPUT" ]]; then
  echo "No input dir: $INPUT"
  exit 2
fi

# -------------------------------
# 1) Build fat jar (skip tests)
# -------------------------------
( cd "$ROOT" && ./gradlew -q -x test shadowJar )

JAR="$ROOT/build/libs/daikonplusplus.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing fat jar: $JAR"
  exit 3
fi

mkdir -p "$EXPECTED"
BUILDDIR="$ROOT/build/e2e/$CASE"
mkdir -p "$BUILDDIR"
REG="$BUILDDIR/registry.jsonl"
OUT="$BUILDDIR/outcomes.jsonl"
rm -f "$REG" "$OUT"

# -------------------------------
# 2) Environment setup
# -------------------------------
# Real LLM allowed
unset DP_DISABLE_REAL_LLM

# Capture cassettes in a predictable path
export DP_LLM_CASSETTES="$ROOT/src/test/cassettes"
mkdir -p "$DP_LLM_CASSETTES"

# -------------------------------
# 3) Read config.json (if exists)
# -------------------------------
MAIN_CLASS="com.example.Main"
MAXK="5"
EXEC_MODE="native"
CMD_ARGS=()

if [[ -f "$CASEDIR/config.json" ]]; then
  if command -v jq >/dev/null 2>&1; then
    EXEC_MODE="$(jq -r '.execMode // "native"' "$CASEDIR/config.json")"
    MAIN_CLASS="$(jq -r '.mainClass // "com.example.Main"' "$CASEDIR/config.json")"
    MAXK="$(jq -r '.maxK // 5' "$CASEDIR/config.json")"

    if [[ "$EXEC_MODE" == "external" ]]; then
      CMD_ARGS=()
      while IFS= read -r line; do
        CMD_ARGS+=("$line")
      done < <(jq -r '.command[]' "$CASEDIR/config.json")
    fi
  else
    # very minimal fallback (native-only)
    v=$(grep -o '"mainClass"\s*:\s*"[^"]*"' "$CASEDIR/config.json" | sed -E 's/.*"([^"]+)".*/\1/')
    [[ -n "${v:-}" ]] && MAIN_CLASS="$v"
    v=$(grep -o '"maxK"\s*:\s*[0-9]+' "$CASEDIR/config.json" | sed -E 's/.*:\s*([0-9]+).*/\1/')
    [[ -n "${v:-}" ]] && MAXK="$v"
  fi
fi
# -------------------------------
# 4) Run the app
# -------------------------------
if [[ "$EXEC_MODE" == "external" ]]; then
  echo "[record] execMode=external"
  echo "[record] command: ${CMD_ARGS[*]}"

java -Dfile.encoding=UTF-8 \
     -Ddp.registry="$REG" \
     -Ddp.outcomes="$OUT" \
     -Ddp.registryReset=true \
     -jar "$JAR" \
     --external-project \
     --project-root "$INPUT" \
     --main-src "." \
     --runner-script "run.sh"


else
  echo "[record] execMode=native"
  echo "[record] mainClass=$MAIN_CLASS"

  java -Dfile.encoding=UTF-8 \
       -Ddp.registry="$REG" \
       -Ddp.outcomes="$OUT" \
       -Ddp.registryReset=true \
       -jar "$JAR" \
       "$INPUT" "" "$MAIN_CLASS" "$MAXK"
fi

# -------------------------------
# 5) Cleanup
# -------------------------------
unset DP_LLM_CASSETTES

# -------------------------------
# 6) Save expected snapshots
# -------------------------------
cp "$REG" "$EXPECTED/registry.jsonl"
cp "$OUT" "$EXPECTED/outcomes.jsonl"

echo "✅ Snapshot + cassette recorded for $CASE"
echo "    → Expected: $EXPECTED"
echo "    → Cassette: $ROOT/src/test/cassettes"
