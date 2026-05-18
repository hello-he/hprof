#!/usr/bin/env bash
# Compare global diagnostics: mem-analyze (JAR) vs hprof_parser.py on the same HPROF.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MEM_ANALYZE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HPROF_PARSER_DIR="${HPROF_PARSER_DIR:-$HOME/workspaces/Android-App-Memory-Analysis/tools}"
JAR="${MEM_ANALYZE_JAR:-$MEM_ANALYZE_ROOT/build/libs/mem-analyze-1.0.0-all.jar}"

usage() {
  echo "Usage: $0 -f <file.hprof> [-o output_dir] [--top N]"
  echo ""
  echo "  Builds JAR if missing, exports JSON from both tools, prints diff report."
  echo "  Env: HPROF_PARSER_DIR, MEM_ANALYZE_JAR"
  exit 1
}

HPROF=""
OUT_DIR=""
TOP=20

while [[ $# -gt 0 ]]; do
  case "$1" in
    -f|--hprof) HPROF="$2"; shift 2 ;;
    -o|--output) OUT_DIR="$2"; shift 2 ;;
    --top) TOP="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown arg: $1"; usage ;;
  esac
done

[[ -n "$HPROF" ]] || usage
[[ -f "$HPROF" ]] || { echo "ERROR: hprof not found: $HPROF"; exit 1; }

if [[ ! -f "$JAR" ]]; then
  echo ">> Building shadowJar..."
  (cd "$MEM_ANALYZE_ROOT" && ./gradlew shadowJar -x test --no-daemon -q)
fi

if [[ -z "$OUT_DIR" ]]; then
  base="$(basename "$HPROF" .hprof)"
  OUT_DIR="$MEM_ANALYZE_ROOT/build/compare-reports/${base}_$(date +%Y%m%d_%H%M%S)"
fi
mkdir -p "$OUT_DIR"

exec python3 "$SCRIPT_DIR/compare_hprof_diagnostics.py" \
  -f "$HPROF" \
  -o "$OUT_DIR" \
  --jar "$JAR" \
  --parser-dir "$HPROF_PARSER_DIR" \
  --top "$TOP"
