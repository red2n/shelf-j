// Gateway rate limit: each client IP gets shelfj.gateway.rate-limit.requests-per-minute requests in
// a 60-second window, then 429 RATE_LIMITED with Retry-After — and never a 5xx.
//
// Fires a little over the budget within one window at a path the gateway itself rejects (no token
// → 401), so no business service carries the load. RATE_LIMIT_REQUESTS_PER_MINUTE must match the
// gateway's setting; k6/run.sh reads it from the running container (docker-compose sets 30000).
// Every request from this host is refused for the rest of the window; k6/run.sh clears the counter
// in the local Redis afterwards.
//
//   k6/run.sh gateway-rate-limit-stress
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE } from './lib/shelfj.js';

const LIMIT = Number(__ENV.RATE_LIMIT_REQUESTS_PER_MINUTE || 30000);
const OVER = Math.max(200, Math.round(LIMIT * 0.02));
const BATCH = 25;

const admitted = new Counter('admitted_requests');
const limited = new Counter('rate_limited_requests');

export const options = {
  scenarios: {
    burst: { executor: 'shared-iterations', vus: 40, iterations: Math.ceil((LIMIT + OVER) / BATCH), maxDuration: '50s' },
  },
  thresholds: {
    checks: ['rate==1.0'],
    // Up to the budget gets through (fewer if this IP already spent some of this window)...
    admitted_requests: [`count<=${LIMIT}`],
    // ...and past it the limiter answers. Fails if the host could not send LIMIT requests in 50 s.
    rate_limited_requests: ['count>0'],
  },
};

const URL = `${BASE}/api/order-svc/orders`;

export default function () {
  const responses = http.batch(Array.from({ length: BATCH }, () => ['GET', URL, null, { tags: { name: 'GET /api/order-svc/orders (no token)' } }]));
  for (const res of responses) {
    const isLimited = res.status === 429;
    (isLimited ? limited : admitted).add(1);
    check(res, {
      'under budget → 401 from the gateway, over → 429 RATE_LIMITED, never 5xx': (r) =>
        r.status === 401 || (r.status === 429 && r.body.includes('RATE_LIMITED') && r.headers['Retry-After'] === '60'),
    });
  }
}
