import http from 'k6/http';
import { check } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-pricing-${Date.now()}@example.com`;
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
    JSON.stringify({ businessName: `k6-pricing-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'GB', currency: 'GBP' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return { uid, tenantId };
}

const FAKE_UUID = '00000000-0000-0000-0000-000000000001';

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const hdrs = tenantId ? { ...JSON_CT, 'X-Tenant-Id': tenantId, 'X-Roles': 'OWNER' } : { ...JSON_CT };
  const noTenant = { ...JSON_CT };

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  const vatRes = http.post(
    `${baseUrl}/api/pricing-svc/vat-rates`,
    JSON.stringify({ code: 'T1', name: 'Standard', rate: 0.2, exempt: false, description: '20%', effectiveFrom: '2020-01-01T00:00:00Z' }),
    { headers: hdrs }
  );
  check(vatRes, { '[+] create vat rate 201': (r) => r.status === 201 });

  check(
    http.get(`${baseUrl}/api/pricing-svc/vat-rates`, { headers: hdrs }),
    { '[+] list vat rates 200': (r) => r.status === 200 }
  );

  check(
    http.get(`${baseUrl}/api/pricing-svc/vat-rates/T1`, { headers: hdrs }),
    { '[+] get vat rate 200': (r) => r.status === 200 }
  );

  // [-] Duplicate code → 409
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/vat-rates`,
      JSON.stringify({ code: 'T1', name: 'Dup', rate: 0.2, exempt: false, effectiveFrom: '2020-01-01T00:00:00Z' }),
      { headers: hdrs }
    ),
    { '[-] duplicate vat code 409': (r) => r.status === 409 }
  );

  // [-] Missing code → 400
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/vat-rates`,
      JSON.stringify({ name: 'No Code', rate: 0.2, exempt: false, effectiveFrom: '2020-01-01T00:00:00Z' }),
      { headers: hdrs }
    ),
    { '[-] vat rate missing code 400': (r) => r.status === 400 }
  );

  // ── Price Lists ───────────────────────────────────────────────────────────

  const plRes = http.post(
    `${baseUrl}/api/pricing-svc/price-lists`,
    JSON.stringify({ name: `k6-pl-${Date.now()}`, channel: 'POS', currency: 'GBP', effectiveFrom: '2024-01-01T00:00:00Z' }),
    { headers: hdrs }
  );
  check(plRes, { '[+] create price list 201': (r) => r.status === 201 });
  const plId = plRes.status === 201 ? plRes.json('data.id') : null;

  check(
    http.get(`${baseUrl}/api/pricing-svc/price-lists`, { headers: hdrs }),
    { '[+] list price lists 200': (r) => r.status === 200 }
  );

  if (plId) {
    check(
      http.get(`${baseUrl}/api/pricing-svc/price-lists/${plId}`, { headers: hdrs }),
      { '[+] get price list 200': (r) => r.status === 200 }
    );

    check(
      http.post(
        `${baseUrl}/api/pricing-svc/price-lists/${plId}/items`,
        JSON.stringify({ variantId: FAKE_UUID, price: 9.99, minQty: 1 }),
        { headers: hdrs }
      ),
      { '[+] upsert price list item 200': (r) => r.status === 200 }
    );

    check(
      http.get(`${baseUrl}/api/pricing-svc/price-lists/${plId}/items`, { headers: hdrs }),
      { '[+] list price list items 200': (r) => r.status === 200 }
    );

    // [-] Upsert item missing price → 400
    check(
      http.post(
        `${baseUrl}/api/pricing-svc/price-lists/${plId}/items`,
        JSON.stringify({ variantId: FAKE_UUID }),
        { headers: hdrs }
      ),
      { '[-] upsert price list item missing price 400': (r) => r.status === 400 }
    );
  }

  // ── Gap #41: Price Overrides ──────────────────────────────────────────────

  const overrideRes = http.post(
    `${baseUrl}/api/pricing-svc/admin/price-overrides`,
    JSON.stringify({
      variantId: FAKE_UUID,
      storeId: FAKE_UUID,
      originalPrice: 9.99,
      overridePrice: 7.50,
      overrideReason: 'Manager discount',
      overriddenBy: FAKE_UUID
    }),
    { headers: hdrs }
  );
  check(overrideRes, { '[+] create price override 201': (r) => r.status === 201 });

  check(
    http.get(`${baseUrl}/api/pricing-svc/admin/price-overrides`, { headers: hdrs }),
    { '[+] list price overrides 200': (r) => r.status === 200 }
  );

  check(
    http.get(`${baseUrl}/api/pricing-svc/admin/price-overrides?storeId=${FAKE_UUID}`, { headers: hdrs }),
    { '[+] list price overrides by store 200': (r) => r.status === 200 }
  );

  check(
    http.get(`${baseUrl}/api/pricing-svc/admin/price-overrides?variantId=${FAKE_UUID}`, { headers: hdrs }),
    { '[+] list price overrides by variant 200': (r) => r.status === 200 }
  );

  // [-] Override with missing variantId → 400
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/admin/price-overrides`,
      JSON.stringify({ storeId: FAKE_UUID, overridePrice: 5.00 }),
      { headers: hdrs }
    ),
    { '[-] price override missing variantId 400': (r) => r.status === 400 }
  );

  // [-] Override with missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/admin/price-overrides`,
      JSON.stringify({ variantId: FAKE_UUID, overridePrice: 5.00 }),
      { headers: hdrs }
    ),
    { '[-] price override missing storeId 400': (r) => r.status === 400 }
  );

  // [-] Override with negative price → 400
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/admin/price-overrides`,
      JSON.stringify({ variantId: FAKE_UUID, storeId: FAKE_UUID, overridePrice: -1.00 }),
      { headers: hdrs }
    ),
    { '[-] price override negative price 400': (r) => r.status === 400 }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/pricing-svc/admin/price-overrides`,
      JSON.stringify({ variantId: FAKE_UUID, storeId: FAKE_UUID, overridePrice: 5.00 }),
      { headers: noTenant }
    ),
    { '[-] price override no auth 403': (r) => r.status === 403 }
  );
}
