#!/usr/bin/env bash
#
# Bring up the Shelf-J Flutter web app (storefront + POS + admin console) on
# :40015, pointed at the dockerized gateway (http://localhost:8090/api).
#
# Usage:
#   ./scripts/run-web.sh                 # storefront product deep-link (default)
#   ./scripts/run-web.sh --pos           # open the in-store POS till
#   ./scripts/run-web.sh --admin         # open the admin console login
#   ./scripts/run-web.sh --platform      # open the platform-admin console login
#   ./scripts/run-web.sh <tenantId> <productId>   # storefront, custom ids
#
# Notes:
#   - Profile mode (dart2js single bundle) is used on purpose: debug/DDC web
#     hangs on a full-page refresh. Profile is refresh-safe (trade-off: ~25s
#     compile, no hot reload).
#   - Served via `-d web-server` so any deep-link URL (with ?tenant=&#/route)
#     works; the script auto-opens the URL once the dev server is ready.
#   - Press 'q' in this terminal to stop.
set -euo pipefail

# Prefer a non-snap Flutter SDK if present: snap-confine can't run in some
# sandboxed/containerized dev environments (missing capabilities), which
# breaks the snap-packaged `flutter` even though the SDK itself is fine.
if [ -x "${HOME}/development/flutter/bin/flutter" ]; then
  export PATH="${HOME}/development/flutter/bin:${PATH}"
fi

PORT="${POS_WEB_PORT:-40015}"
GATEWAY="${SHELFJ_GATEWAY:-http://localhost:8090}"

# Parse an optional mode flag, then positional <tenantId> <productId>.
MODE="store"
case "${1:-}" in
  --pos)      MODE="pos";      shift ;;
  --admin)    MODE="admin";    shift ;;
  --platform) MODE="platform"; shift ;;
  --store)    MODE="store";    shift ;;
esac
TENANT="${1:-046e140b-2d6d-40d0-a96b-4550e47e051c}"
PRODUCT="${2:-d12e7d86-9cfe-4ac5-a67d-1a3daab8f66b}"

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../frontends/shelf-app" && pwd)"

# The storefront is tenant-scoped via ?tenant= (guest); POS/admin are staff-authed
# (tenant comes from the JWT), so they need no tenant in the URL.
case "${MODE}" in
  pos)      URL="http://localhost:${PORT}/#/pos/cart" ;;
  admin)    URL="http://localhost:${PORT}/#/login" ;;
  platform) URL="http://localhost:${PORT}/#/platform/login" ;;
  *)        URL="http://localhost:${PORT}/?tenant=${TENANT}#/store/products/${PRODUCT}" ;;
esac

cyan() { printf '\033[1;36m%s\033[0m\n' "$*"; }
red()  { printf '\033[1;31m%s\033[0m\n' "$*"; }

# 1. Gateway must be up — the app is useless without the API.
if ! curl -fsS -o /dev/null --max-time 5 "${GATEWAY}/health/live"; then
  red "✗ Gateway not reachable at ${GATEWAY}"
  red "  Start the backend first:  docker compose up -d"
  exit 1
fi
cyan "✓ Gateway healthy at ${GATEWAY}"

# 2. Warn (don't block) if the tenant is suspended. A suspended tenant 403s the
#    storefront AND blocks its staff login (so POS/admin are unreachable too).
if [ "${MODE}" = "store" ]; then
  SUSPENDED_MSG="storefront will return 403"
else
  SUSPENDED_MSG="staff login will be blocked (TENANT_INACTIVE)"
fi
ACTIVE=$(curl -fsS --max-time 5 "${GATEWAY}/api/tenant-svc/storefront/active" \
  -H "X-Storefront-Tenant: ${TENANT}" 2>/dev/null || true)
case "${ACTIVE}" in
  *'"active":true'*)  cyan "✓ Tenant ${TENANT} is ACTIVE" ;;
  *'"active":false'*) red "! Tenant is INACTIVE — ${SUSPENDED_MSG}. Reactivate it in the Platform console." ;;
  *)                  red "! Could not confirm tenant status (continuing anyway)." ;;
esac

cd "${APP_DIR}"

# 3. Ensure deps, then serve. Auto-open the deep-link once the server answers.
cyan "→ flutter pub get"
flutter pub get >/dev/null

cyan ""
cyan "Opening once compiled:"
cyan "  ${URL}"
cyan ""
cyan "Other entry points (same server):"
cyan "  Admin console : http://localhost:${PORT}/#/login"
cyan "  In-store POS  : http://localhost:${PORT}/#/pos/cart   (staff login + clock-in)"
cyan ""

(
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null "http://localhost:${PORT}" 2>/dev/null; then
      command -v xdg-open >/dev/null 2>&1 && xdg-open "${URL}" >/dev/null 2>&1 || true
      break
    fi
    sleep 2
  done
) &

exec flutter run -d web-server --profile --web-port="${PORT}" --web-hostname=localhost
