#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   scripts/new-baseline.sh /path/to/src_root [case-name] [maxK]
#
# Examples:
#   scripts/new-baseline.sh ~/projects/foo/src  "foo-baseline" 5
#   scripts/new-baseline.sh /tmp/miniproj
#
# Notes:
# - src_root should be the root that contains your Java packages (e.g., com/...).
# - We will create: src/test/resources/daikonpp-pipeline/NN-case-name/{input,expected,config.json}
# - We auto-detect mainClass by looking for `public static void main(String[]` and its package.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIPE_DIR="$ROOT_DIR/src/test/resources/daikonpp-pipeline"

SRC_ROOT="${1:-}"
if [[ -z "$SRC_ROOT" || ! -d "$SRC_ROOT" ]]; then
  echo "ERROR: Please provide a valid source root directory."
  exit 1
fi
SRC_ROOT="$(cd "$SRC_ROOT" && pwd)"

CASE_NAME="${2:-}"
MAXK="${3:-5}"

# Compute next NN prefix (00, 01, 02, ...)
next_index() {
  local max=-1 n
  shopt -s nullglob
  for d in "$PIPE_DIR"/*; do
    base="$(basename "$d")"
    if [[ "$base" =~ ^([0-9]{2})- ]]; then
      n="${BASH_REMATCH[1]}"
      if [[ "$n" =~ ^[0-9]+$ ]] && ((10#$n > max)); then
        max=$((10#$n))
      fi
    fi
  done
  printf "%02d" $((max + 1))
}

if [[ -z "$CASE_NAME" ]]; then
  # default case-name from folder name of SRC_ROOT
  CASE_SLUG="$(basename "$SRC_ROOT" | tr '[:space:]' '-' | tr '[:upper:]' '[:lower:]')"
  CASE_NAME="$CASE_SLUG"
fi

NN="$(next_index)"
CASE_DIR="$PIPE_DIR/$NN-$CASE_NAME"
INPUT_DIR="$CASE_DIR/input"
EXPECTED_DIR="$CASE_DIR/expected"

echo "=> Creating case at: $CASE_DIR"
mkdir -p "$INPUT_DIR" "$EXPECTED_DIR"

echo "=> Copying sources from: $SRC_ROOT"
# Copy sources (keeping package paths)
rsync -a --delete --exclude '.git' --exclude 'build' --exclude 'out' --exclude '.gradle' "$SRC_ROOT"/ "$INPUT_DIR"/

# Try to detect mainClass by scanning for a main method
echo "=> Detecting mainClass..."
MAIN_FILE="$(grep -Rl --include='*.java' 'public static void main(String[]' "$INPUT_DIR" | head -n1 || true)"
if [[ -z "$MAIN_FILE" ]]; then
  echo "WARN: Could not detect a main class automatically. You'll need to edit config.json later."
  MAIN_CLASS="com.example.Main"
else
  PKG_LINE="$(grep -m1 '^package ' "$MAIN_FILE" || true)"
  CLS_NAME="$(basename "$MAIN_FILE" .java)"
  if [[ -n "$PKG_LINE" ]]; then
    PKG_NAME="$(echo "$PKG_LINE" | sed -E 's/^package[[:space:]]+([^;]+);.*/\1/')"
    MAIN_CLASS="$PKG_NAME.$CLS_NAME"
  else
    MAIN_CLASS="$CLS_NAME"
  fi
fi

# Write config.json (you can extend with other knobs later)
cat > "$CASE_DIR/config.json" <<EOF
{
  "mainClass": "$MAIN_CLASS",
  "maxK": $MAXK
}
EOF

echo "=> Wrote config.json:"
cat "$CASE_DIR/config.json"

echo "=> Priming expected snapshot (using cassettes if present)"
# You can flip this to 0 to hit the real LLM and generate cassettes.
export DP_LLM_CASSETTES="${DP_LLM_CASSETTES:-src/test/cassettes}"
export DP_DISABLE_REAL_LLM="${DP_DISABLE_REAL_LLM:-1}"

# Run only this one case by passing a filter property the test understands.
./gradlew -q test -Ddp.updateSnapshots=true -Ddp.onlyCase="$NN-$CASE_NAME" --tests "*PipelineE2ETest.*"

echo
echo "✅ Done."
echo "Case directory:"
echo "  $CASE_DIR"
echo "You can now run:"
echo "  DP_DISABLE_REAL_LLM=1 ./gradlew test --tests \"*PipelineE2ETest.*\""

