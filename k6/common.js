import http from 'k6/http';
import { check } from 'k6';

export const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
export const loginPath = __ENV.LOGIN_PATH || '/api/iam-svc/auth/login';
export const invalidCredentials = JSON.parse(
  __ENV.INVALID_CREDENTIALS || '{"email":"invalid-user@example.com","password":"invalid-pass"}'
);
export const validCredentials = JSON.parse(
  __ENV.VALID_CREDENTIALS || '{"email":"good-user@example.com","password":"good-pass"}'
);
export const maxFailures = Number(__ENV.BRUTE_FORCE_MAX_FAILURES || 5);
export const blockedStatus = Number(__ENV.BRUTE_FORCE_BLOCK_STATUS || 429);
export const expectedRateLimit = Number(__ENV.RATE_LIMIT_REQUESTS_PER_MINUTE || 100);

export function login(payload) {
  const url = `${baseUrl}${loginPath}`;
  return http.post(url, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
}

export function assertResponse(res, expectedStatuses) {
  check(res, {
    [`status is one of ${expectedStatuses.join(', ')}`]: (r) =>
      expectedStatuses.includes(r.status),
  });
}
