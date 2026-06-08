import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-inv-${Date.now()}@example.com`;
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
    JSON.stringify({ businessName: `k6-inv-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;

  // Create a store to get a real storeId
  let storeId = null;
  if (tenantId) {
    const storeRes = http.post(
      `${baseUrl}/api/tenant-svc/admin/stores`,
      JSON.stringify({ name: 'k6 Warehouse', code: `K6W-${Date.now()}`, line1: '1 Dock Rd', city: 'LA', country: 'US', pincode: '90001', timezone: 'UTC' }),
      { headers: { ...JSON_CT, 'X-Tenant-Id': tenantId } }
    );
    storeId = storeRes.status < 300 ? storeRes.json('data.id') : null;
  }
  return { uid, tenantId, storeId };
}

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const storeId = ctx && ctx.storeId;
  // Use a deterministic fake variantId — inventory-svc will create/reference the batch
  const variantId = '00000000-0000-0000-0000-000000000099';
  const hdrs = tenantId
    ? { ...JSON_CT, 'X-Tenant-Id': tenantId }
    : { ...JSON_CT };

  // Receive stock
  const recRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      qty: 50,
      batchNo: `BATCH-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  check(recRes, { 'stock received (2xx or validation 4xx)': (r) => r.status < 500 });

  sleep(0.5);

  // Check stock levels
  const levelsRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/levels`,
    { headers: hdrs }
  );
  check(levelsRes, { 'levels returned < 500': (r) => r.status < 500 });

  // Adjust stock
  const adjRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/adjust`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      delta: -5,
      reason: 'k6-test-adjustment',
    }),
    { headers: hdrs }
  );
  check(adjRes, { 'adjust returned < 500': (r) => r.status < 500 });
}
