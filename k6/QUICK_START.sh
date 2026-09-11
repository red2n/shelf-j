#!/usr/bin/env bash
# Flow guard quick start: check the prerequisites, run both flow-guard suites, then validate the
# database. See k6/README.md for every suite and for writing new ones.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8090}"

echo "1. gateway"
if ! curl -fsS -o /dev/null "$BASE_URL/health"; then
  echo "   gateway not reachable at $BASE_URL — start the stack: docker compose up -d --build" >&2
  exit 1
fi
echo "   up at $BASE_URL"

echo "2. k6"
if ! command -v k6 >/dev/null; then
  echo "   k6 not installed — https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 1
fi
echo "   $(k6 version | head -1)"

echo "3. flow guards (onboarding → sale, then suspend / close / reopen)"
k6/run.sh flow-guard-comprehensive flow-guard-runtime

echo "4. database"
if command -v psql >/dev/null; then
  k6/db/validate_all.sh
else
  echo "   psql not installed — skipping (k6/db/validate_all.sh)"
fi
