// Gateway smoke test: health, a public read, sign-up, sign-in and an authenticated call round-trip
// through the gateway. The quickest "is the stack wired up" check after a deploy.
//
//   k6/run.sh gateway-smoke-it
import http from 'k6/http';
import { ALL_CHECKS_PASS, BASE, call, data, expect, login, register, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

export default function () {
  expect(http.get(`${BASE}/health`), 'gateway health', 200);
  expect(call('GET', '/api/iam-svc/openapi'), 'a service contract is reachable through the gateway', 200);
  const user = register('smoke');
  const session = login(user);
  expect(session, 'login', 200);
  const me = call('GET', '/api/iam-svc/auth/me', { token: data(session).accessToken });
  expect(me, 'authenticated call', 200);
  truthy('the gateway forwarded the right identity', data(me).email === user.email, data(me));
  expect(call('GET', '/api/tenant-svc/admin/tenant'), 'no token is refused at the gateway', 401);
  expect(call('GET', '/api/nope-svc/anything', { token: data(session).accessToken }), 'unknown service', [403, 404]);
}
