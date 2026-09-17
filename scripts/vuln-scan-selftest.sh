#!/usr/bin/env bash
#
# Shows the vulnerability gate doing its job (22.11) on a bill of materials that is known to be
# bad — log4j-core 2.14.1, Log4Shell — and the exceptions file refusing anything that is not a
# dated, reasoned decision. Usage: scripts/vuln-scan-selftest.sh   (exit 0 only when all pass)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCAN="$ROOT/scripts/vuln-scan.sh"
BAD="$ROOT/scripts/testdata/vulnerable-bom.cdx.json"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SOON="$(date -d '+30 days' +%F)"

passed=0
failed=0
# expect <exit status wanted> <text the output must hold, or -> <label> <exceptions file> <args...>
expect() {
  local want="$1" text="$2" label="$3" file="$4"
  shift 4
  EXCEPTIONS="$file" "$SCAN" "$@" >"$WORK/out" 2>&1
  local got=$?
  if [ "$got" -eq "$want" ] && { [ "$text" = "-" ] || grep -q -- "$text" "$WORK/out"; }; then
    printf '  ok    %s\n' "$label"; passed=$((passed + 1))
  else
    printf '  FAIL  %s (exit %s, wanted %s)\n' "$label" "$got" "$want"; tail -4 "$WORK/out" | sed 's/^/        /'; failed=$((failed + 1))
  fi
}
entry() { # entry <id> <package> <reason> <decided-by> <expires>
  printf -- '  - id: "%s"\n    package: "%s"\n    reason: "%s"\n    decided-by: "%s"\n    expires: %s\n' "$@"
}
REASON="The fixture is scanned on purpose and nothing ever runs it"

echo "exceptions: []" > "$WORK/none.yaml"
expect 1 "GHSA-jfh8-c2jp-5v3q" "[+] Log4Shell in a bill of materials fails the gate, by name" "$WORK/none.yaml" sbom "$BAD"

{ echo "exceptions:"
  for id in GHSA-jfh8-c2jp-5v3q GHSA-7rjr-3q55-vv33 GHSA-p6xc-xr62-6r2g; do entry "$id" log4j-core "$REASON" "the self-test" "$SOON"; done
} > "$WORK/decided.yaml"
expect 0 "3 excepted" "[+] three dated, reasoned decisions let it through, and the scan says how many" "$WORK/decided.yaml" sbom "$BAD"

{ echo "exceptions:"
  for id in GHSA-jfh8-c2jp-5v3q GHSA-7rjr-3q55-vv33; do entry "$id" log4j-core "$REASON" "the self-test" "$SOON"; done
} > "$WORK/two.yaml"
expect 1 "GHSA-p6xc-xr62-6r2g" "[-] two decisions out of three: the third still fails it" "$WORK/two.yaml" sbom "$BAD"

{ echo "exceptions:"; entry GHSA-jfh8-c2jp-5v3q log4j-core "accepted" "the self-test" "$SOON"; } > "$WORK/word.yaml"
expect 1 "not a sentence" "[-] a one-word reason is not a decision" "$WORK/word.yaml" exceptions

{ echo "exceptions:"; entry GHSA-jfh8-c2jp-5v3q log4j-core "$REASON" "the self-test" "$(date -d '-1 day' +%F)"; } > "$WORK/expired.yaml"
expect 1 "expired on" "[-] a decision past its date fails the scan until it is made again" "$WORK/expired.yaml" exceptions

{ echo "exceptions:"; entry GHSA-jfh8-c2jp-5v3q log4j-core "$REASON" "the self-test" "$(date -d '+200 days' +%F)"; } > "$WORK/forever.yaml"
expect 1 "ninety days" "[-] nor may one be put off for most of a year" "$WORK/forever.yaml" exceptions

{ echo "exceptions:"; entry GHSA-jfh8-c2jp-5v3q log4j-core "$REASON" "" "$SOON"; } > "$WORK/nobody.yaml"
expect 1 "no decided-by" "[-] a decision nobody made" "$WORK/nobody.yaml" exceptions

{ echo "exceptions:"; entry "" log4j-core "$REASON" "the self-test" "$SOON"; } > "$WORK/blanket.yaml"
expect 1 "no id" "[abuse] an entry with no advisory would except everything in the package: refused" "$WORK/blanket.yaml" exceptions

{ echo "exceptions:"
  for id in GHSA-jfh8-c2jp-5v3q GHSA-7rjr-3q55-vv33 GHSA-p6xc-xr62-6r2g; do entry "$id" some-other-package "$REASON" "the self-test" "$SOON"; done
} > "$WORK/elsewhere.yaml"
expect 1 "GHSA-jfh8-c2jp-5v3q" "[abuse] the right advisory excepted in another package does not cover this one" "$WORK/elsewhere.yaml" sbom "$BAD"

expect 0 "in force" "[+] the repository's own exceptions file is a list of decisions" "$ROOT/security/vulnerability-exceptions.yaml" exceptions

echo
echo "vulnerability gate self-test: ${passed} passed, ${failed} failed"
[ "$failed" -eq 0 ]
