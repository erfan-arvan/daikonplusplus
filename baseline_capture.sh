# ----------------------------------------------------------------------
# baseline_capture.sh
#
# Purpose:
#   Run Daikon++ on the sample project once and capture the *complete*
#   set of artifacts produced by the pipeline as a stable baseline for
#   regression testing.  This baseline represents the “ground truth”
#   outputs of the LLM + pipeline at a known commit and model.
#
# What it does:
#   1. Locates a built Daikon++ JAR automatically (or uses $JAR if set).
#   2. Runs Daikon++ on sample-project/src sample.Main with MAXK invariants.
#   3. Copies key outputs into testdata/daikonpp_runs/baseline/:
#        - app-console.log       (full console output)
#        - registry.jsonl        (final filtered LLM invariants)
#        - daikonpp-run.log      (execution log)
#        - injected-src/         (final Java sources after injection)
#        - workcopy/             (complete working copy snapshot)
#   4. Writes manifest.json with metadata:
#        commit hash, OpenAI model, main class, etc.
#
# Usage:
#     ./baseline_capture.sh
#
# Environment variables:
#     JAR=path/to/daikonplusplus.jar   # optional override
#     DP_OPENAI_MODEL=gpt-4o-mini      # recorded in manifest
#
# After running, commit the baseline directory to git:
#     git add testdata/daikonpp_runs/baseline
#
# Later regression tests re-run the full pipeline and compare all
# generated artifacts to this baseline to ensure that future changes
# (in code or model) do not alter deterministic outputs.
# ----------------------------------------------------------------------

#!/usr/bin/env bash
set -euo pipefail
JAR="${JAR:-}"
if [[ -z "${JAR}" ]]; then
  for cand in "build/libs/daikonplusplus-all.jar" build/libs/daikonplusplus-all-*.jar build/libs/*-all.jar build/libs/*-shadow.jar build/libs/daikonplusplus.jar build/libs/*.jar; do [[ -f "$cand" ]] && JAR="$cand" && break; done
fi
[[ -f "${JAR}" ]] || { echo "jar not found; set JAR=path/to.jar"; exit 1; }
SRC="${SRC:-sample-project/src}"
MAIN="${MAIN:-sample.Main}"
CP="${CP:-.}"
MAXK="${MAXK:-5}"
export DP_KEEP_WORK=1
DEST="testdata/daikonpp_runs/baseline"
mkdir -p "$DEST"
tee_log="$DEST/app-console.log"
java -jar "$JAR" "$SRC" "$CP" "$MAIN" "$MAXK" | tee "$tee_log"
REAL_REG="$(awk -F': ' '/^>>> Registry: /{print $2}' "$tee_log" | tail -n1)"
if [[ -n "${REAL_REG:-}" && -f "$REAL_REG" ]]; then cp "$REAL_REG" "$DEST/registry.jsonl"; fi
WORK_PARENT="${DP_WORKDIR:-build/daikonpp_work}"
if [[ -d "$WORK_PARENT" ]]; then WORKDIR="$(ls -dt "$WORK_PARENT"/src-* 2>/dev/null | head -n1 || true)"; else WORKDIR=""; fi
[[ -n "${WORKDIR:-}" && -d "$WORKDIR" ]] || { echo "workdir not found"; exit 1; }
[[ -f "$WORKDIR/daikonpp-run.log" ]] && cp "$WORKDIR/daikonpp-run.log" "$DEST/daikonpp-run.log" || true
rm -rf "$DEST/injected-src" "$DEST/workcopy" "$DEST/daikonpp-classes"
mkdir -p "$DEST/injected-src" "$DEST/workcopy"
if compgen -G "$WORKDIR/*.java" > /dev/null; then cp -R "$WORKDIR"/*.java "$DEST/injected-src/" || true; fi
find "$WORKDIR" -type f -name '*.java' -exec bash -lc 't="'"$DEST"'/injected-src/${1#'"$WORKDIR"'/}"; mkdir -p "$(dirname "$t")"; cp "$1" "$t"' _ {} \;
cp -R "$WORKDIR"/* "$DEST/workcopy/" || true
git_rev="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
model="${DP_OPENAI_MODEL:-unknown}"
printf '{"git_commit":"%s","openai_model":"%s","src_root":"%s","main_class":"%s","max_invariants":"%s","paths":{"registry":"%s","run_log":"%s","injected_src":"injected-src","workcopy":"workcopy","console_log":"app-console.log"}}\n' "$git_rev" "$model" "$SRC" "$MAIN" "$MAXK" "$( [ -f "$DEST/registry.jsonl" ] && echo registry.jsonl || echo )" "$( [ -f "$DEST/daikonpp-run.log" ] && echo daikonpp-run.log || echo )" > "$DEST/manifest.json"
echo "$DEST"

