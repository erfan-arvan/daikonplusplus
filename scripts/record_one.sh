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
if [[ -f "$CASEDIR/config.json" ]]; then
  if command -v jq >/dev/null 2>&1; then
    MAIN_CLASS="$(jq -r '.mainClass // "com.example.Main"' "$CASEDIR/config.json")"
    MAXK="$(jq -r '.maxK // 5' "$CASEDIR/config.json")"
  else
    v=$(grep -o '"mainClass"\s*:\s*"[^"]*"' "$CASEDIR/config.json" 2>/dev/null | sed -E 's/.*"mainClass"\s*:\s*"([^"]*)".*/\1/')
    [[ -n "${v:-}" ]] && MAIN_CLASS="$v"
    v=$(grep -o '"maxK"\s*:\s*[0-9]+' "$CASEDIR/config.json" 2>/dev/null | sed -E 's/.*:\s*([0-9]+).*/\1/')
    [[ -n "${v:-}" ]] && MAXK="$v"
  fi
fi

# -------------------------------
# 4) Run the app
# -------------------------------
java -Dfile.encoding=UTF-8 \
     -Ddp.registry="$REG" \
     -Ddp.outcomes="$OUT" \
     -Ddp.registryReset=true \
     -jar "$JAR" \
     "$INPUT" "" "$MAIN_CLASS" "$MAXK"

# outcomes may be missing; create empty so tests don’t choke
[[ -f "$OUT" ]] || : > "$OUT"

# Copy results into expected folder
cp "$REG" "$EXPECTED/registry.jsonl"
cp "$OUT" "$EXPECTED/outcomes.jsonl"

# -------------------------------
# 5) Cleanup
# -------------------------------
unset DP_LLM_CASSETTES

echo "✅ Snapshot + cassette recorded for $CASE"
echo "    → Expected: $EXPECTED"
echo "    → Cassette: $ROOT/src/test/cassettes"
