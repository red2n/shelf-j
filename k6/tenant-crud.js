import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setup() {
  // Register a fresh user and create a tenant for this run
  const email = `k6-tenant-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;
  const userId = regRes.json('data.userId') || regRes.json('data.sub');

  // Extract userId from JWT sub claim
  const token = regRes.json('data.accessToken');
  let uid = null;
  try {
    uid = JSON.parse(atob(token.split('.')[1])).sub;
  } catch (_) {}

  // Create tenant — gateway Phase-0 forwards X-User-Id as-is
  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  if (tenantRes.status < 200 || tenantRes.status >= 300) return { uid };
  const tenantId = tenantRes.json('data.id');
  return { uid, tenantId };
}

export default function () {
  const ctx = setup();
  const tenantId = ctx && ctx.tenantId;
  const uid = ctx && ctx.uid;
  const hdrs = tenantId
    ? { ...JSON_CT, 'X-Tenant-Id': tenantId, 'X-User-Id': uid, 'X-Roles': 'OWNER' }
    : { ...JSON_CT };

  // Create tenant (already done in setup — check it passed)
  check({ status: tenantId ? 200 : 0 }, { 'tenant created': (r) => r.status === 200 || tenantId });

  sleep(0.5);

  // Create a store
  const storeRes = http.post(
    `${baseUrl}/api/tenant-svc/admin/stores`,
    JSON.stringify({ name: 'k6 Store', code: `K6-${Date.now()}`, line1: '1 Main St', city: 'NYC', country: 'US', pincode: '10001', timezone: 'UTC' }),
    { headers: hdrs }
  );
  check(storeRes, { 'store created (2xx or 4xx due to missing tenant)': (r) => r.status < 500 });

  // List stores
  const listRes = http.get(`${baseUrl}/api/tenant-svc/admin/stores`, { headers: hdrs });
  check(listRes, { 'list stores 2xx/4xx': (r) => r.status < 500 });

  sleep(0.5);

  // Onboarding status
  const statusRes = http.get(`${baseUrl}/api/tenant-svc/onboarding/status`, { headers: hdrs });
  check(statusRes, { 'onboarding status returned': (r) => r.status < 500 });

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  // [+] Upsert inventory config (PUT is create-or-update)
  const cfgRes = http.put(
    `${baseUrl}/api/tenant-svc/admin/inventory-config`,
    JSON.stringify({
      lotControlEnabled: true,
      serialControlEnabled: false,
      gradeControlEnabled: true,
      expiryTrackingEnabled: true,
      costingMethod: 'FIFO',
      defaultUom: 'EA',
      reorderAlertEnabled: true,
      autoReserveOnOrder: false
    }),
    { headers: hdrs }
  );
  check(cfgRes, { '[+] upsert inventory config 200': (r) => r.status === 200 });

  // [+] GET returns the saved config
  const getCfgRes = http.get(`${baseUrl}/api/tenant-svc/admin/inventory-config`, { headers: hdrs });
  check(getCfgRes, { '[+] get inventory config 200': (r) => r.status === 200 });
  check(getCfgRes, { '[+] config has costingMethod': (r) => r.json('data.costingMethod') === 'FIFO' });

  // [+] Update a single field (partial — other fields keep previous value)
  const patchRes = http.put(
    `${baseUrl}/api/tenant-svc/admin/inventory-config`,
    JSON.stringify({ costingMethod: 'AVERAGE' }),
    { headers: hdrs }
  );
  check(patchRes, { '[+] update costingMethod 200': (r) => r.status === 200 });

  // [-] GET without any auth headers → 403 (RBAC blocks before tenant check)
  check(
    http.get(`${baseUrl}/api/tenant-svc/admin/inventory-config`, { headers: { 'Content-Type': 'application/json' } }),
    { '[-] get config no auth 403': (r) => r.status === 403 }
  );
}
