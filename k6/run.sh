#!/usr/bin/env bash
# Run k6 suites against the dockerized stack and fail if any check fails.
#
#   k6/run.sh                      # the flow guards, then every functional suite
#   k6/run.sh flow-guard-runtime   # one or more suites by name
#   k6/run.sh --load               # the concurrent multi-tenant load runs
#
# BASE_URL defaults to the local gateway. The platform-admin login comes from the environment or,
# failing that, from .env at the repo root.
set -uo pipefail
cd "$(dirname "$0")/.."

env_value() { grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2-; }
export BASE_URL="${BASE_URL:-http://localhost:8090}"
export PLATFORM_ADMIN_EMAIL="${PLATFORM_ADMIN_EMAIL:-$(env_value PLATFORM_ADMIN_EMAIL)}"
export PLATFORM_ADMIN_PASSWORD="${PLATFORM_ADMIN_PASSWORD:-$(env_value PLATFORM_ADMIN_PASSWORD)}"

FUNCTIONAL=(
  flow-guard-comprehensive flow-guard-runtime
  iam-crud tenant-crud product-crud inventory-crud pricing-crud order-crud notification-crud reporting-crud
  privacy-flow compliance-flow purchase-crud vat-return-flow
  gateway-smoke-it gateway-login-protection gateway-unsubscribe-protection gateway-card-data-guard
)
LOAD=(multi-tenant-retail full-stack-simulation gateway-rate-limit-stress)

if [[ $# -eq 0 ]]; then
  suites=("${FUNCTIONAL[@]}")
elif [[ "$1" == "--load" ]]; then
  suites=("${LOAD[@]}")
else
  suites=("$@")
fi

if ! curl -fsS -o /dev/null "$BASE_URL/health"; then
  echo "gateway not reachable at $BASE_URL — start the stack with: docker compose up -d" >&2
  exit 2
fi

# A container can report healthy before Consul lists it, and the gateway answers 503 until it
# does. Each service's OpenAPI document is public through the gateway, so wait for all of them.
services=(iam tenant product inventory pricing cart order payment purchase customer notification reporting)
for attempt in $(seq 1 60); do
  missing=()
  for svc in "${services[@]}"; do
    curl -fs -o /dev/null "$BASE_URL/api/$svc-svc/openapi" || missing+=("$svc-svc")
  done
  [[ ${#missing[@]} -eq 0 ]] && break
  if [[ $attempt -eq 60 ]]; then
    echo "not routable through the gateway after 3 minutes: ${missing[*]}" >&2
    exit 2
  fi
  sleep 3
done

# The gateway suites deliberately lock this host out (brute force: 15 minutes) or spend its rate
# budget (60 seconds). When the stack runs here, clear those counters so later suites — and the web
# app on this machine — are not caught by them. Against a remote stack nothing is cleared.
clear_gateway_counters() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx shelfj-redis || return 0
  local password
  password="${REDIS_PASSWORD:-$(env_value REDIS_PASSWORD)}"
  docker exec -e REDISCLI_AUTH="${password:-redis_dev_change_me}" shelfj-redis sh -c \
    "redis-cli --scan --pattern 'bruteforce:*' | xargs -r redis-cli del >/dev/null;
     redis-cli --scan --pattern 'ratelimit:*' | xargs -r redis-cli del >/dev/null"
}

gateway_setting() {
  docker inspect shelfj-gateway --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | grep -m1 "^$1=" | cut -d= -f2-
}
export RATE_LIMIT_REQUESTS_PER_MINUTE="${RATE_LIMIT_REQUESTS_PER_MINUTE:-$(gateway_setting shelfj.gateway.rate-limit.requests-per-minute)}"
[[ -z "$RATE_LIMIT_REQUESTS_PER_MINUTE" ]] && unset RATE_LIMIT_REQUESTS_PER_MINUTE

logs="${K6_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$logs"
failed=0
clear_gateway_counters
printf '\n%-28s %-6s %s\n' SUITE RESULT CHECKS
for suite in "${suites[@]}"; do
  log="$logs/$suite.log"
  k6 run --quiet "k6/$suite.js" >"$log" 2>&1
  code=$?
  [[ "$suite" == gateway-* ]] && clear_gateway_counters
  checks=$(grep -E '^\s*checks_succeeded' "$log" | tr -s ' ' | cut -d' ' -f3-)
  if [[ $code -eq 0 ]]; then result=pass; else result=FAIL; failed=1; fi
  printf '%-28s %-6s %s\n' "$suite" "$result" "${checks:-see log}"
done
echo
echo "logs: $logs"
exit $failed
