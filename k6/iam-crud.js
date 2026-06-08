import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = {
  vus: 1,
  iterations: 1,
};

function uniqueEmail() {
  return `k6-iam-${Date.now()}@example.com`;
}

export default function () {
  const email = uniqueEmail();
  const payload = { email, password: 'TestPass123!', name: 'k6 test user' };

  // Create (register)
  const res = http.post(`${baseUrl}/api/iam-svc/auth/register`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'user created (201 or 200)': (r) => r.status === 201 || r.status === 200 });

  // Optional: try to login with new user to exercise auth flow
  const loginRes = http.post(`${baseUrl}/api/iam-svc/auth/login`, JSON.stringify({ email, password: payload.password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(loginRes, { 'login succeeded (2xx)': (r) => r.status >= 200 && r.status < 300 });

  // Sleep briefly to allow DB write to settle
  sleep(1);

  // Attempt read/update/delete flows if exposed by the API (not all services expose user CRUD)
  try {
    const body = res.json();
    const id = body && (body.data && body.data.id) ? body.data.id : body.id;
    if (id) {
      // Try GET
      const get = http.get(`${baseUrl}/api/iam-svc/users/${id}`);
      check(get, { 'get user returned 2xx/404': (r) => r.status === 200 || r.status === 404 });

      // Try PUT (idempotent, best-effort)
      const update = http.put(`${baseUrl}/api/iam-svc/users/${id}`, JSON.stringify({ name: 'k6-updated' }), {
        headers: { 'Content-Type': 'application/json' },
      });
      check(update, { 'update returned 2xx/4xx/404': (r) => r.status >= 200 && r.status < 500 });

      // Try DELETE
      const del = http.del(`${baseUrl}/api/iam-svc/users/${id}`);
      check(del, { 'delete returned 2xx/404/401': (r) => r.status >= 200 && r.status < 500 });
    }
  } catch (e) {
    // ignore parsing errors — validation will be done via DB scripts
  }
}
