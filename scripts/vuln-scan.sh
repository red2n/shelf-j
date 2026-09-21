#!/usr/bin/env bash
#
# Scans what StoreQL ships for known vulnerabilities (22.11) and fails on any High or Critical one
# that is not a current, reasoned exception in security/vulnerability-exceptions.yaml. The same
# script runs on a desk, in the Vulnerability scan workflow (pull requests, main, and every night —
# new advisories arrive after the build) and in the publish workflow before an image is signed.
#
# Usage: scripts/vuln-scan.sh deps                  the reactor's SBOM (every shipped jar) and the
#                                                   app's Dart packages (pubspec.lock)
#        scripts/vuln-scan.sh sbom <file.cdx.json>  any CycloneDX SBOM
#        scripts/vuln-scan.sh image <ref>           an image: OS packages and everything in it
#        scripts/vuln-scan.sh exceptions            only check the exceptions file
# Env:   GRYPE=<path>      the grype to use (default: tools/grype, fetched with its checksum)
#        SARIF_DIR=<dir>   also write <name>.sarif there, for code scanning
#        FAIL_ON=high      the severity that fails the scan (default high)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXCEPTIONS="${EXCEPTIONS:-$ROOT/security/vulnerability-exceptions.yaml}"
FAIL_ON="${FAIL_ON:-high}"
GRYPE_V=0.118.0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export GRYPE_CHECK_FOR_APP_UPDATE=false

grype_bin() {
  if [ -n "${GRYPE:-}" ]; then echo "$GRYPE"; return; fi
  if [ ! -x "$ROOT/tools/grype" ]; then
    mkdir -p "$ROOT/tools"
    local base="https://github.com/anchore/grype/releases/download/v${GRYPE_V}"
    local file="grype_${GRYPE_V}_linux_amd64.tar.gz"
    curl -fsSL --max-time 600 -o "$WORK/$file" "$base/$file" && curl -fsSL --max-time 60 -o "$WORK/sums" "$base/grype_${GRYPE_V}_checksums.txt" || return 1
    [ "$(grep " $file\$" "$WORK/sums" | cut -d' ' -f1)" = "$(sha256sum "$WORK/$file" | cut -d' ' -f1)" ] \
      || { echo "grype: checksum mismatch — not installed" >&2; return 1; }
    tar -xzf "$WORK/$file" -C "$ROOT/tools" grype
  fi
  echo "$ROOT/tools/grype"
}

# The exceptions file → a grype config. Refuses an entry that is not a decision: no reason, no
# name, no date, a date already past or more than ninety days away.
exceptions_config() {
  python3 - "$EXCEPTIONS" "$WORK/grype.yaml" "$FAIL_ON" <<'PY'
import datetime, sys
import yaml
src, out, fail_on = sys.argv[1:4]
doc = yaml.safe_load(open(src)) or {}
entries = doc.get("exceptions") or []
today = datetime.date.today()
problems, rules = [], []
for i, e in enumerate(entries, 1):
    e = e or {}
    who = f"exception {i} ({e.get('id', 'no id')})"
    for field in ("id", "package", "reason", "decided-by", "expires"):
        if not str(e.get(field, "")).strip():
            problems.append(f"{who}: no {field}")
    if len(str(e.get("reason", "")).split()) < 5:
        problems.append(f"{who}: the reason is not a sentence")
    try:
        expires = e.get("expires")
        if not isinstance(expires, datetime.date):
            expires = datetime.date.fromisoformat(str(expires))
        if expires < today:
            problems.append(f"{who}: expired on {expires} — decide again or fix it")
        elif (expires - today).days > 90:
            problems.append(f"{who}: expires more than ninety days out")
    except ValueError:
        problems.append(f"{who}: expires is not a date")
    rules.append({"vulnerability": str(e.get("id", "")), "package": {"name": str(e.get("package", ""))}})
if problems:
    print("security/vulnerability-exceptions.yaml:", *problems, sep="\n  ", file=sys.stderr)
    sys.exit(1)
yaml.safe_dump({"fail-on-severity": fail_on, "ignore": rules}, open(out, "w"))
print(f"exceptions: {len(rules)} in force")
PY
}

scan() { # scan <name> <grype source>
  local name="$1" source="$2" grype
  grype="$(grype_bin)" || exit 2
  local outputs=(-o "table" -o "json=$WORK/$name.json")
  [ -n "${SARIF_DIR:-}" ] && mkdir -p "$SARIF_DIR" && outputs+=(-o "sarif=$SARIF_DIR/$name.sarif")
  echo "== $name: $source"
  "$grype" "$source" -c "$WORK/grype.yaml" -q "${outputs[@]}" | sed 's/^/   /'
  local status=${PIPESTATUS[0]}
  python3 - "$WORK/$name.json" <<'PY'
import collections, json, sys
d = json.load(open(sys.argv[1]))
found = collections.Counter(m["vulnerability"]["severity"] for m in d.get("matches", []))
ignored = len(d.get("ignoredMatches") or [])
print("   findings:", dict(found) or "none", f"· {ignored} excepted" if ignored else "")
PY
  return "$status"
}

mode="${1:-}"
exceptions_config || exit 1
case "$mode" in
  exceptions) exit 0 ;;
  deps)
    [ -f "$ROOT/target/storeql-bom.json" ] || "$ROOT/scripts/sbom.sh" || exit 2
    failed=0
    scan reactor "sbom:$ROOT/target/storeql-bom.json" || failed=1
    scan app "dir:$ROOT/frontends/storeql-app" || failed=1
    ;;
  sbom) failed=0; scan sbom "sbom:${2:?an SBOM file}" || failed=1 ;;
  image) failed=0; scan image "${2:?an image reference}" || failed=1 ;;
  *) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
if [ "$failed" -ne 0 ]; then
  echo "vulnerability scan: findings at ${FAIL_ON} or above — fix them, or record a dated decision in security/vulnerability-exceptions.yaml" >&2
  exit 1
fi
echo "vulnerability scan: nothing at ${FAIL_ON} or above"
