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
  const variantId = '00000000-0000-0000-0000-000000000099';
  const hdrs = tenantId ? { ...JSON_CT, 'X-Tenant-Id': tenantId } : { ...JSON_CT };

  // ── Gap #1-#7: core inventory positive checks ─────────────────────────────

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
  check(recRes, { '[+] receive stock 201': (r) => r.status === 201 });

  sleep(0.5);

  const levelsRes = http.get(`${baseUrl}/api/inventory-svc/admin/inventory/levels`, { headers: hdrs });
  check(levelsRes, { '[+] levels 200': (r) => r.status === 200 });

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
  check(adjRes, { '[+] adjust stock 200': (r) => r.status === 200 });

  // ── Gap #9: ABC Analysis — positive checks ───────────────────────────────

  // Compile with no demand history → run created, 0 items compiled
  const abcCompileEmptyRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({}),
    { headers: hdrs }
  );
  check(abcCompileEmptyRes, {
    '[+] abc compile 201': (r) => r.status === 201,
    '[+] abc compile has itemsCompiled': (r) => {
      try { return typeof r.json('data.itemsCompiled') === 'number'; } catch (_) { return false; }
    },
  });

  // Compile with explicit VALUE criteria and custom thresholds
  const abcCompileValueRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      criteria: 'VALUE',
      thresholdA: 70,
      thresholdAB: 90,
    }),
    { headers: hdrs }
  );
  check(abcCompileValueRes, {
    '[+] abc compile VALUE 201': (r) => r.status === 201,
    '[+] abc compile criteria is VALUE': (r) => {
      try { return r.json('data.criteria') === 'VALUE'; } catch (_) { return false; }
    },
  });

  // Compile with VELOCITY criteria
  const abcCompileVelocityRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VELOCITY' }),
    { headers: hdrs }
  );
  check(abcCompileVelocityRes, {
    '[+] abc compile VELOCITY 201': (r) => r.status === 201,
  });

  // List ABC assignments (may be empty if no demand data)
  const abcListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments`,
    { headers: hdrs }
  );
  check(abcListRes, {
    '[+] list abc assignments 200': (r) => r.status === 200,
    '[+] abc assignments is array': (r) => {
      try { return Array.isArray(r.json('data')); } catch (_) { return false; }
    },
  });

  // List filtered by class A
  const abcListARes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments?class=A`,
    { headers: hdrs }
  );
  check(abcListARes, {
    '[+] list abc class=A 200': (r) => r.status === 200,
  });

  // Compile is idempotent (re-run same params → 201, previous overwritten)
  const abcReRunRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 70, thresholdAB: 90 }),
    { headers: hdrs }
  );
  check(abcReRunRes, {
    '[+] abc re-compile is idempotent (201)': (r) => r.status === 201,
  });

  sleep(0.3);

  // ── Gap #9: ABC Analysis — negative checks ────────────────────────────────

  // Invalid criteria
  const abcBadCriteriaRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'COST' }),
    { headers: hdrs }
  );
  check(abcBadCriteriaRes, {
    '[-] invalid abc criteria → 400': (r) => r.status === 400,
  });

  // thresholdA >= thresholdAB
  const abcBadThreshRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 90, thresholdAB: 70 }),
    { headers: hdrs }
  );
  check(abcBadThreshRes, {
    '[-] thresholdA >= thresholdAB → 400': (r) => r.status === 400,
  });

  // thresholdA = 0
  const abcZeroThreshRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 0, thresholdAB: 90 }),
    { headers: hdrs }
  );
  check(abcZeroThreshRes, {
    '[-] thresholdA = 0 → 400': (r) => r.status === 400,
  });

  // Invalid class filter
  const abcBadClassRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments?class=X`,
    { headers: hdrs }
  );
  check(abcBadClassRes, {
    '[-] invalid abc class filter → 400': (r) => r.status === 400,
  });

  // Get assignment for nonexistent variant → 404
  const abcNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments/` +
      `00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000000`,
    { headers: hdrs }
  );
  check(abcNotFoundRes, {
    '[-] get non-existent abc assignment → 404': (r) => r.status === 404,
  });

  // No tenant header → 4xx
  const abcNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE' }),
    { headers: JSON_CT }
  );
  check(abcNoTenantRes, {
    '[-] abc compile no X-Tenant-Id → 4xx': (r) => r.status >= 400 && r.status < 500,
  });

  sleep(0.3);

  // ── Gap #8: Safety Stock — positive checks ────────────────────────────────

  // Set safety stock params (MAD method)
  const ssMADRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      method: 'MAD',
      leadTimeDays: 7,
      serviceLevelPct: 95,
    }),
    { headers: hdrs }
  );
  check(ssMADRes, {
    '[+] set safety stock MAD 201': (r) => r.status === 201,
    '[+] safety stock method is MAD': (r) => {
      try { return r.json('data.method') === 'MAD'; } catch (_) { return false; }
    },
    '[+] safety stock leadTimeDays is 7': (r) => {
      try { return r.json('data.leadTimeDays') === 7; } catch (_) { return false; }
    },
  });

  // Set safety stock params (USER_DEFINED method)
  const variantId2 = '00000000-0000-0000-0000-000000000098';
  const ssUDRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId: variantId2,
      method: 'USER_DEFINED',
      leadTimeDays: 14,
      userDefinedPct: 25,
    }),
    { headers: hdrs }
  );
  check(ssUDRes, {
    '[+] set safety stock USER_DEFINED 201': (r) => r.status === 201,
    '[+] safety stock method is USER_DEFINED': (r) => {
      try { return r.json('data.method') === 'USER_DEFINED'; } catch (_) { return false; }
    },
  });

  // List safety stock params
  const ssListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    { headers: hdrs }
  );
  check(ssListRes, {
    '[+] list safety stock 200': (r) => r.status === 200,
    '[+] safety stock list is array': (r) => {
      try { return Array.isArray(r.json('data')); } catch (_) { return false; }
    },
  });

  // Compute safety stock (no demand history yet → returns 0 or rows=N computed)
  const ssComputeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/compute`,
    JSON.stringify({ storeId: storeId || '00000000-0000-0000-0000-000000000001' }),
    { headers: hdrs }
  );
  check(ssComputeRes, {
    '[+] compute safety stock 200': (r) => r.status === 200,
    '[+] compute result has computed field': (r) => {
      try { return typeof r.json('data.computed') === 'number'; } catch (_) { return false; }
    },
  });

  // Get single safety stock params by storeId/variantId
  if (storeId) {
    const ssGetRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/${storeId}/${variantId}`,
      { headers: hdrs }
    );
    check(ssGetRes, {
      '[+] get safety stock params 200': (r) => r.status === 200,
      '[+] get safety stock params returns storeId': (r) => {
        try { return typeof r.json('data.storeId') === 'string'; } catch (_) { return false; }
      },
    });
  }

  // Upsert idempotency: re-posting same variant should update (not 409)
  const ssUpsertRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      method: 'MAD',
      leadTimeDays: 10,
      serviceLevelPct: 98,
    }),
    { headers: hdrs }
  );
  check(ssUpsertRes, {
    '[+] upsert safety stock is idempotent (2xx)': (r) => r.status < 300,
    '[+] upsert updates leadTimeDays to 10': (r) => {
      try { return r.json('data.leadTimeDays') === 10; } catch (_) { return false; }
    },
  });

  sleep(0.3);

  // ── Gap #8: Safety Stock — negative checks ────────────────────────────────

  // Missing method field (should default gracefully or return 400)
  const ssMissingVariantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      // variantId missing
      method: 'MAD',
    }),
    { headers: hdrs }
  );
  check(ssMissingVariantRes, {
    '[-] missing variantId → 400': (r) => r.status === 400,
  });

  // Invalid method value
  const ssBadMethodRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId: '00000000-0000-0000-0000-000000000097',
      method: 'INVALID_METHOD',
    }),
    { headers: hdrs }
  );
  check(ssBadMethodRes, {
    '[-] invalid method → 400': (r) => r.status === 400,
  });

  // USER_DEFINED without userDefinedPct
  const ssMissingPctRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId: '00000000-0000-0000-0000-000000000096',
      method: 'USER_DEFINED',
      // userDefinedPct missing
    }),
    { headers: hdrs }
  );
  check(ssMissingPctRes, {
    '[-] USER_DEFINED without userDefinedPct → 400': (r) => r.status === 400,
  });

  // Get non-existent safety stock params → 404
  const ssNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/` +
      `00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000000`,
    { headers: hdrs }
  );
  check(ssNotFoundRes, {
    '[-] get non-existent safety stock → 404': (r) => r.status === 404,
  });

  // No tenant header → 401
  const ssNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({ storeId: '00000000-0000-0000-0000-000000000001', variantId, method: 'MAD' }),
    { headers: JSON_CT }
  );
  check(ssNoTenantRes, {
    '[-] no X-Tenant-Id → 4xx': (r) => r.status >= 400 && r.status < 500,
  });

  // Invalid UUID for storeId
  const ssBadUUIDRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({ storeId: 'not-a-uuid', variantId, method: 'MAD' }),
    { headers: hdrs }
  );
  check(ssBadUUIDRes, {
    '[-] invalid storeId UUID → 400': (r) => r.status === 400,
  });
}
