import http from 'k6/http';
import { check } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-notif-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-notif-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': '01a090ae-611e-7001-a690-2682e4afcb55' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return tenantId;
}

export default function () {
  const tenantId = setupTenant();
  const hdrs = (extra = {}) => ({ ...JSON_CT, 'X-Tenant-Id': tenantId || '01a090ae-611e-702c-a97b-d1b8025478e1', 'X-Roles': 'OWNER', ...extra });

  // ── Positive: list shortage alerts (empty initially) ─────────────────────
  const list1 = http.get(`${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts`, { headers: hdrs() });
  check(list1, {
    'list alerts 200': r => r.status === 200,
    'list alerts has data array': r => Array.isArray(r.json('data')),
    'list alerts empty initially': r => r.json('data').length === 0,
  });

  // ── Positive: filter by storeId ────────────────────────────────────────────
  const storeId = '01a090ae-611e-700f-b645-a14095230b77';
  const listByStore = http.get(
    `${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts?storeId=${storeId}`,
    { headers: hdrs() }
  );
  check(listByStore, {
    'list by store 200': r => r.status === 200,
  });

  // ── Positive: filter by variantId ─────────────────────────────────────────
  const variantId = '01a090ae-611e-7011-ae7d-1bd68c966ff6';
  const listByVariant = http.get(
    `${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts?variantId=${variantId}`,
    { headers: hdrs() }
  );
  check(listByVariant, {
    'list by variant 200': r => r.status === 200,
  });

  // ── Positive: limit capped at 100 ─────────────────────────────────────────
  const listBig = http.get(
    `${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts?limit=500`,
    { headers: hdrs() }
  );
  check(listBig, {
    'oversized limit 200': r => r.status === 200,
  });

  // ── Negative: missing tenant header → 401 ────────────────────────────────
  const noTenant = http.get(
    `${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts`,
    { headers: { ...JSON_CT, 'X-Roles': 'OWNER' } }
  );
  check(noTenant, {
    'no tenant 401': r => r.status === 401,
  });

  // ── Negative: wrong role → 403 ────────────────────────────────────────────
  const wrongRole = http.get(
    `${baseUrl}/api/notification-svc/admin/notifications/shortage-alerts`,
    { headers: { ...JSON_CT, 'X-Tenant-Id': tenantId || '01a090ae-611e-702c-a97b-d1b8025478e1', 'X-Roles': 'CUSTOMER' } }
  );
  check(wrongRole, {
    'wrong role 403': r => r.status === 403,
  });
}
