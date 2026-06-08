import { group, sleep } from 'k6';
import { login, assertResponse, invalidCredentials } from './common.js';

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
    http_req_failed: ['rate<0.2'],
  },
};

export function default() {
  group('Gateway rate-limit stress test', () => {
    const res = login(invalidCredentials);
    assertResponse(res, [401, 429]);
    sleep(0.1);
  });
}
