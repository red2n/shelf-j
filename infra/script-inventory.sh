#!/bin/sh
# The inventory of every script the web shell serves, with a reason for each, and its hash.
#
# PCI DSS v4.0.1 keeps a merchant on SAQ-A only while its own site cannot be altered by scripts;
# reqs 6.4.3 and 11.6.1 ask for an inventory of the scripts on the pages that lead to payment,
# a reason for each, and tamper detection. Card entry never happens in this shell (it is the
# provider's hosted page, reached by redirect); the shell still leads there, so every script it
# serves is listed here at image build, and the gateway's ScriptIntegrityMonitor checks the served
# scripts against this file on a schedule and raises an alert when one changes or appears.
#
# Usage: script-inventory.sh <html-dir>   — writes <html-dir>/script-inventory.json;
# fails the build on a script it cannot give a reason for, so nothing ships unexplained.
set -eu
root="$(cd "${1:?html dir}" && pwd)"
out="$root/script-inventory.json"

reason_for() {
  case "$1" in
    first_frame.js) echo "Paints the first frame before the app loads, so the policy can refuse every inline script (12.11)." ;;
    flutter_bootstrap.js) echo "Flutter's loader, emitted by the SDK at build time; starts the engine and the app." ;;
    flutter.js) echo "Flutter's web engine loader, emitted by the SDK." ;;
    flutter_service_worker.js) echo "The service worker Flutter emits, which caches the bundle for a returning browser." ;;
    main.dart.js) echo "The app itself, compiled from lib/ by dart2js." ;;
    main.dart.js_*.part.js) echo "A deferred part of the app (one shell), compiled from lib/ by dart2js." ;;
    canvaskit/*.js) echo "The CanvasKit renderer, served from this origin rather than a CDN." ;;
    *) return 1 ;;
  esac
}

tmp="$out.tmp"
{
  printf '{"generatedAt":"%s",' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '"policy":"Card entry never happens in this shell: it is the payment provider'"'"'s hosted page, reached by redirect (PCI DSS SAQ-A). Every script the shell serves is listed here with its reason; the gateway checks the served scripts against this inventory on a schedule and alerts on a change or an unlisted script.",'
  printf '"scripts":['
  first=1
  cd "$root"
  find . -type f \( -name '*.js' -o -name '*.mjs' \) | sed 's#^\./##' | sort | while read -r path; do
    if ! reason="$(reason_for "$path")"; then
      echo "script-inventory: no reason for $path — add it to infra/script-inventory.sh or remove it" >&2
      exit 1
    fi
    sum="$(sha256sum "$path" | cut -d' ' -f1)"
    bytes="$(wc -c < "$path" | tr -d ' ')"
    if [ "$first" = 1 ]; then first=0; else printf ','; fi
    printf '{"path":"%s","bytes":%s,"sha256":"%s","reason":"%s"}' "$path" "$bytes" "$sum" "$reason"
  done
  printf ']}\n'
} > "$tmp"
mv "$tmp" "$out"
echo "script-inventory: $(grep -o '"path"' "$out" | wc -l | tr -d ' ') scripts listed in $out"
