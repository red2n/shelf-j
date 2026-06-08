import { group } from 'k6';
import { login, validCredentials } from './common.js';

export const options = {
  scenarios: {
    valid_login: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      exec: 'valid_login',
    },
  },
};

export function valid_login() {
  group('Gateway integration smoke test', () => {
    const res = login(validCredentials);
    if (!(res.status >= 200 && res.status < 300)) {
      throw new Error(`Expected valid login to succeed, got ${res.status}: ${res.body}`);
    }
  });
}
