import http from 'k6/http';
import { check } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-report-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-report-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': '00000000-0000-0000-0000-000000000001' } }
  );
  return tenantRes.status < 300 ? tenantRes.json('data.id') : null;
}

export default function () {
  const tenantId = setupTenant() || 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  const hdrs = (extra = {}) => ({ ...JSON_CT, 'X-Tenant-Id': tenantId, 'X-Roles': 'OWNER', ...extra });
  const base = `${baseUrl}/api/reporting-svc/admin/reports/inventory`;

  // ── Gap #47: on-hand ──────────────────────────────────────────────────────
  const onHand = http.get(`${base}/on-hand`, { headers: hdrs() });
  check(onHand, {
    'on-hand 200': r => r.status === 200,
    'on-hand has data': r => r.json('data') !== null,
    'on-hand has grandTotal': r => r.json('data.grandTotal') !== undefined,
    'on-hand rows array': r => Array.isArray(r.json('data.rows')),
  });

  // ── Gap #47: on-hand filtered by storeId ─────────────────────────────────
  const storeId = '11111111-1111-1111-1111-111111111111';
  const onHandByStore = http.get(`${base}/on-hand?storeId=${storeId}`, { headers: hdrs() });
  check(onHandByStore, { 'on-hand by store 200': r => r.status === 200 });

  // ── Gap #48: supply/demand netting ───────────────────────────────────────
  const netting = http.get(`${base}/supply-demand`, { headers: hdrs() });
  check(netting, {
    'supply-demand 200': r => r.status === 200,
    'supply-demand has rows': r => Array.isArray(r.json('data.rows')),
  });

  // ── Gap #48: netting filtered by variantId ────────────────────────────────
  const variantId = '22222222-2222-2222-2222-222222222222';
  const nettingByVariant = http.get(`${base}/supply-demand?variantId=${variantId}`, { headers: hdrs() });
  check(nettingByVariant, { 'supply-demand by variant 200': r => r.status === 200 });

  // ── Gap #49: movement stats (default bucket=7 days) ───────────────────────
  const stats = http.get(`${base}/movement-stats`, { headers: hdrs() });
  check(stats, {
    'movement-stats 200': r => r.status === 200,
    'movement-stats has rows': r => Array.isArray(r.json('data.rows')),
  });

  // ── Gap #49: movement stats with day bucket ───────────────────────────────
  const statsDay = http.get(`${base}/movement-stats?bucketDays=1`, { headers: hdrs() });
  check(statsDay, { 'movement-stats day bucket 200': r => r.status === 200 });

  // ── Gap #49: movement stats with month bucket ─────────────────────────────
  const statsMonth = http.get(`${base}/movement-stats?bucketDays=30`, { headers: hdrs() });
  check(statsMonth, { 'movement-stats month bucket 200': r => r.status === 200 });

  // ── Negative: missing tenant header → 401 ────────────────────────────────
  const noTenant = http.get(`${base}/on-hand`, { headers: { ...JSON_CT, 'X-Roles': 'OWNER' } });
  check(noTenant, { 'no tenant 401': r => r.status === 401 });

  // ── Negative: wrong role → 403 ────────────────────────────────────────────
  const wrongRole = http.get(`${base}/on-hand`, { headers: { ...JSON_CT, 'X-Tenant-Id': tenantId, 'X-Roles': 'CUSTOMER' } });
  check(wrongRole, { 'wrong role 403': r => r.status === 403 });
}
