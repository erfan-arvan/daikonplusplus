#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# record_one.sh — Record an E2E test snapshot for a single case.
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
# 1) Build fat jar
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
# 2) Environment
# -------------------------------
unset DP_DISABLE_REAL_LLM

export DP_LLM_CASSETTES="$ROOT/src/test/cassettes"
mkdir -p "$DP_LLM_CASSETTES"

# -------------------------------
# 3) Read config.json
# -------------------------------
MAIN_CLASS="com.example.Main"
MAXK="5"
EXEC_MODE="native"
CMD_ARGS=()
DP_PROPS=()
declare -a DP_PROPS

if [[ -f "$CASEDIR/config.json" ]]; then
  if command -v jq >/dev/null 2>&1; then

    EXEC_MODE="$(jq -r '.execMode // "native"' "$CASEDIR/config.json")"
    MAIN_CLASS="$(jq -r '.mainClass // "com.example.Main"' "$CASEDIR/config.json")"
    MAXK="$(jq -r '.maxK // 5' "$CASEDIR/config.json")"

    # ✅ GENERIC dp.* CONFIG PARSER
    if jq -e '.dp' "$CASEDIR/config.json" >/dev/null 2>&1; then
      while IFS="=" read -r key value; do
        DP_PROPS+=("-Ddp.$key=$value")
      done < <(
        jq -r '
          .dp
          | to_entries[]
          | "\(.key)=\(
              if (.value | type) == "array"
              then (.value | join(","))
              else .value
              end
            )"
        ' "$CASEDIR/config.json"
      )
    fi

    if [[ "$EXEC_MODE" == "external" ]]; then
      while IFS= read -r line; do
        CMD_ARGS+=("$line")
      done < <(jq -r '.command[]' "$CASEDIR/config.json")
    fi

  else
    # minimal fallback
    v=$(grep -o '"mainClass"\s*:\s*"[^"]*"' "$CASEDIR/config.json" | sed -E 's/.*"([^"]+)".*/\1/')
    [[ -n "${v:-}" ]] && MAIN_CLASS="$v"

    v=$(grep -o '"maxK"\s*:\s*[0-9]+' "$CASEDIR/config.json" | sed -E 's/.*:\s*([0-9]+).*/\1/')
    [[ -n "${v:-}" ]] && MAXK="$v"
  fi
fi

# -------------------------------
# 4) Run
# -------------------------------
if [[ "$EXEC_MODE" == "external" ]]; then
  echo "[record] execMode=external"

  java -Dfile.encoding=UTF-8 \
       ${DP_PROPS[@]+"${DP_PROPS[@]}"} \
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

  java -Dfile.encoding=UTF-8 \
       ${DP_PROPS[@]+"${DP_PROPS[@]}"} \
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
# 6) Save snapshots
# -------------------------------
cp "$REG" "$EXPECTED/registry.jsonl"
cp "$OUT" "$EXPECTED/outcomes.jsonl"

echo "✅ Snapshot + cassette recorded for $CASE"