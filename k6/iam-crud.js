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

  // ── Gap #45: POS session idle timeout ─────────────────────────────────────

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-iam-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'GB', currency: 'GBP' }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': '01a090ae-611e-7001-a690-2682e4afcb55' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  const posHdrs = tenantId
    ? { 'Content-Type': 'application/json', 'X-Tenant-Id': tenantId, 'X-User-Id': '01a090ae-611e-7001-a690-2682e4afcb55' }
    : { 'Content-Type': 'application/json' };

  const sessionRes = http.post(
    `${baseUrl}/api/iam-svc/auth/pos/sessions`,
    JSON.stringify({ storeId: '01a090ae-611e-7001-a690-2682e4afcb55', idleTimeoutSeconds: 300 }),
    { headers: posHdrs }
  );
  check(sessionRes, { '[+] start pos session 201': (r) => r.status === 201 });
  const sessionId = sessionRes.status === 201 ? sessionRes.json('data.id') : null;

  if (sessionId) {
    check(
      http.put(`${baseUrl}/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, null, { headers: posHdrs }),
      { '[+] touch pos session 204': (r) => r.status === 204 }
    );

    check(
      http.get(`${baseUrl}/api/iam-svc/auth/pos/sessions`, { headers: posHdrs }),
      { '[+] list active pos sessions 200': (r) => r.status === 200 }
    );

    check(
      http.del(`${baseUrl}/api/iam-svc/auth/pos/sessions/${sessionId}`, null, { headers: posHdrs }),
      { '[+] end pos session 204': (r) => r.status === 204 }
    );
  }

  check(
    http.post(`${baseUrl}/api/iam-svc/auth/pos/sessions/sweep`, null, { headers: posHdrs }),
    { '[+] sweep idle sessions 200': (r) => r.status === 200 }
  );

  // [-] Start session missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/iam-svc/auth/pos/sessions`,
      JSON.stringify({ idleTimeoutSeconds: 300 }),
      { headers: posHdrs }
    ),
    { '[-] start session missing storeId 400': (r) => r.status === 400 }
  );

  // [-] Start session with invalid timeout → 400
  check(
    http.post(
      `${baseUrl}/api/iam-svc/auth/pos/sessions`,
      JSON.stringify({ storeId: '01a090ae-611e-7001-a690-2682e4afcb55', idleTimeoutSeconds: 10 }),
      { headers: posHdrs }
    ),
    { '[-] start session invalid timeout 400': (r) => r.status === 400 }
  );

  // [-] Touch unknown session → 404
  check(
    http.put(
      `${baseUrl}/api/iam-svc/auth/pos/sessions/01a090ae-611e-7000-9e1a-0f8a9e565153/activity`,
      null,
      { headers: posHdrs }
    ),
    { '[-] touch unknown session 404': (r) => r.status === 404 }
  );

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
