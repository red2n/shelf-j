#!/usr/bin/env bash
# Run Duplo (duplicate-code finder) over the Java sources.
# Requires the `duplo` binary on PATH or at ./tools/duplo (download from
# https://github.com/dlidstrom/Duplo/releases — duplo-linux.zip).
#
# Usage: scripts/duplo.sh [min-lines]   (default 8)
set -uo pipefail
cd "$(dirname "$0")/.."

MIN_LINES="${1:-8}"
DUPLO="$(command -v duplo || echo ./tools/duplo)"
if [[ ! -x "$DUPLO" ]]; then
  echo "duplo not found. Download duplo-linux.zip from the Duplo releases page to ./tools/duplo" >&2
  exit 1
fi

LIST="$(mktemp)"
find services platform shared -path '*/src/main/java/*' -name '*.java' -not -path '*/target/*' > "$LIST"
echo "Analyzing $(wc -l < "$LIST") Java files (min block = ${MIN_LINES} lines)..."
"$DUPLO" -ml "$MIN_LINES" "$LIST" /tmp/duplo-report.txt >/dev/null
grep -A3 "Results:" /tmp/duplo-report.txt
echo "Full report: /tmp/duplo-report.txt"
rm -f "$LIST"
