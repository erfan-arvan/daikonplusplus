#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# record_all.sh — Record E2E test snapshots for all available cases.
#
# This script iterates over every case directory under:
#   src/test/resources/daikonpp-pipeline/
# and invokes `record_one.sh` for each case that contains an `input/` folder.
#
# It runs the Daikon++ pipeline with *real* LLM calls (DP_DISABLE_REAL_LLM unset),
# regenerates `expected/registry.jsonl` and `expected/outcomes.jsonl` for each case,
# and stores all corresponding LLM cassettes under:
#   src/test/cassettes/
#
# Usage:
#   ./scripts/record_all.sh
#
# Each individual case can also be recorded manually with:
#   ./scripts/record_one.sh <case-name>
#
# Notes:
#   • Requires internet access for live LLM requests.
#   • Should be run only when updating the reference snapshots.
#
# -----------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CASES_ROOT="$ROOT/src/test/resources/daikonpp-pipeline"

for d in "$CASES_ROOT"/*; do
  [[ -d "$d/input" ]] || continue
  CASE="$(basename "$d")"
  echo "=== Recording $CASE ==="
  "$ROOT/scripts/record_one.sh" "$CASE"
done
echo "All snapshots recorded."

