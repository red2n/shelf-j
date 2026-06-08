import { group, sleep } from 'k6';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { login, invalidCredentials } from './common.js';

const rateLimited = new Rate('rate_limited_responses');

export const options = {
  scenarios: {
    burst_load: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    // All responses must be 401 (rejected) or 429 (rate-limited) — never 5xx
    checks: ['rate==1.0'],
    // At least some requests must be rate-limited (proves the limiter fired)
    rate_limited_responses: ['rate>0'],
  },
};

export default function () {
  group('Gateway rate-limit stress test', () => {
    const res = login(invalidCredentials);
    check(res, {
      'status is one of 401, 429': (r) => r.status === 401 || r.status === 429,
    });
    rateLimited.add(res.status === 429 ? 1 : 0);
    sleep(0.1);
  });
}
