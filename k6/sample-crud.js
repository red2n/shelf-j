import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-sample-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;
  let uid = null;
  try {
    const token = regRes.json('data.accessToken');
    uid = JSON.parse(atob(token.split('.')[1])).sub;
  } catch (_) {}

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-sample-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return { uid, tenantId };
}

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const hdrs = tenantId
    ? { ...JSON_CT, 'X-Tenant-Id': tenantId }
    : { ...JSON_CT };

  // Create widget
  const createRes = http.post(
    `${baseUrl}/api/sample-svc/widgets`,
    JSON.stringify({ name: `k6-widget-${Date.now()}`, colour: 'blue' }),
    { headers: hdrs }
  );
  check(createRes, { 'widget created 2xx': (r) => r.status >= 200 && r.status < 300 });

  sleep(0.5);

  // Read and list
  try {
    const id = createRes.json('data.id');
    if (id) {
      const getRes = http.get(`${baseUrl}/api/sample-svc/widgets/${id}`, { headers: hdrs });
      check(getRes, { 'get widget 2xx': (r) => r.status === 200 });
    }
  } catch (_) {}

  const listRes = http.get(`${baseUrl}/api/sample-svc/widgets`, { headers: hdrs });
  check(listRes, { 'list widgets 2xx': (r) => r.status === 200 });
}
