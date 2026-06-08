import { group, sleep } from 'k6';
import { login, assertResponse, invalidCredentials, maxFailures, blockedStatus } from './common.js';

export const options = {
  scenarios: {
    login_protection: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
      exec: 'bruteForceScenario',
    },
  },
};

export function bruteForceScenario() {
  group('Gateway login protection sequence', () => {
    for (let attempt = 1; attempt <= maxFailures + 3; attempt++) {
      const res = login(invalidCredentials);
      const expected = attempt > maxFailures ? [blockedStatus] : [401, blockedStatus];
      assertResponse(res, expected);
      sleep(1);
    }
  });
}
