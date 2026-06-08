import http from 'k6/http';
import { check, group, sleep } from 'k6';

export const options = {
  scenarios: {
    rate_limit: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'rateLimitScenario',
    },
    brute_force: {
      executor: 'constant-vus',
      vus: 1,
      duration: '15s',
      exec: 'bruteForceScenario',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.2'],
    'checks{scenario:brute_force}': ['rate>0.9'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const loginPath = __ENV.LOGIN_PATH || '/auth';
const invalidCredentials = JSON.parse(
  __ENV.INVALID_CREDENTIALS || '{"username":"invalid-user","password":"invalid-pass"}'
);
const maxFailures = Number(__ENV.BRUTE_FORCE_MAX_FAILURES || 5);
const blockedStatus = Number(__ENV.BRUTE_FORCE_BLOCK_STATUS || 429);

function login(payload) {
  const url = `${baseUrl}${loginPath}`;
  const headers = { 'Content-Type': 'application/json' };
  return http.post(url, JSON.stringify(payload), { headers });
}

export function rateLimitScenario() {
  group('Gateway rate-limit smoke test', () => {
    const res = login(invalidCredentials);
    check(res, {
      'status is 401 or 429': (r) => r.status === 401 || r.status === blockedStatus,
    });
    sleep(0.2);
  });
}

export function bruteForceScenario() {
  group('Gateway brute-force protection test', () => {
    for (let attempt = 1; attempt <= maxFailures + 2; attempt++) {
      const res = login(invalidCredentials);
      const blockedPhase = attempt > maxFailures;

      check(res, {
        [`attempt ${attempt} returned 401 or ${blockedStatus}`]: (r) =>
          r.status === 401 || r.status === blockedStatus,
        [`attempt ${attempt} is blocked after threshold`]: (r) =>
          !blockedPhase || r.status === blockedStatus,
      });

      sleep(1);
    }
  });
}
