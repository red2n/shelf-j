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

  // ── Gap #10: Cycle Counting — positive checks ─────────────────────────────

  // Create a cycle count (will pick up ABC assignments from the A,B,C classes)
  const ccCreateRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({
      storeId,
      name: 'Monthly count',
      abcClasses: 'A,B,C',
      tolerancePct: 5,
    }),
    { headers: hdrs }
  );
  check(ccCreateRes, {
    '[+] create cycle count 201': (r) => r.status === 201,
    '[+] cycle count has id': (r) => {
      try { return JSON.parse(r.body).data.id !== undefined; } catch { return false; }
    },
    '[+] cycle count status OPEN': (r) => {
      try { return JSON.parse(r.body).data.status === 'OPEN'; } catch { return false; }
    },
  });

  let ccId = null;
  try { ccId = JSON.parse(ccCreateRes.body).data.id; } catch (_) {}

  // List cycle counts
  const ccListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    { headers: hdrs }
  );
  check(ccListRes, {
    '[+] list cycle counts 200': (r) => r.status === 200,
    '[+] cycle counts list is array': (r) => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch { return false; }
    },
  });

  // Get cycle count by id
  if (ccId) {
    const ccGetRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}`,
      { headers: hdrs }
    );
    check(ccGetRes, {
      '[+] get cycle count 200': (r) => r.status === 200,
      '[+] get cycle count returns name': (r) => {
        try { return JSON.parse(r.body).data.name === 'Monthly count'; } catch { return false; }
      },
    });

    // Enter a count on the first line if any lines exist
    const ccLinesRaw = JSON.parse(ccGetRes.body).data;
    if (ccLinesRaw && ccLinesRaw.totalLines > 0) {
      // We need to get the actual line ids — re-fetch with lines detail
      // Lines are not part of the header response but the endpoint returns them embedded
      // For the k6 test we derive a line id from a separate get (totalLines > 0 means lines exist)
    }

    // Approve within tolerance (no lines yet → autoApproved=0 is fine)
    const ccApproveRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}/approve`,
      null,
      { headers: hdrs }
    );
    check(ccApproveRes, {
      '[+] approve cycle count 200': (r) => r.status === 200,
      '[+] approve result has autoApproved field': (r) => {
        try { return JSON.parse(r.body).data.autoApproved !== undefined; } catch { return false; }
      },
    });

    // Adjust cycle count (promotes PENDING_APPROVAL or OPEN header to ADJUSTED)
    const ccAdjustRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}/adjust`,
      null,
      { headers: hdrs }
    );
    check(ccAdjustRes, {
      '[+] adjust cycle count 200': (r) => r.status === 200,
      '[+] adjust result has adjusted field': (r) => {
        try { return JSON.parse(r.body).data.adjusted !== undefined; } catch { return false; }
      },
    });
  }

  // ── Gap #10: Cycle Counting — negative checks ─────────────────────────────

  // Missing storeId
  const ccNoStoreRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ name: 'Test' }),
    { headers: hdrs }
  );
  check(ccNoStoreRes, {
    '[-] create cycle count missing storeId → 400': (r) => r.status === 400,
  });

  // Missing name
  const ccNoNameRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ storeId }),
    { headers: hdrs }
  );
  check(ccNoNameRes, {
    '[-] create cycle count missing name → 400': (r) => r.status === 400,
  });

  // Get non-existent cycle count → 404
  const ccNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/00000000-0000-0000-0000-000000000099`,
    { headers: hdrs }
  );
  check(ccNotFoundRes, {
    '[-] get non-existent cycle count → 404': (r) => r.status === 404,
  });

  // No tenant header → 401
  const ccNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ storeId, name: 'T' }),
    { headers: JSON_CT }
  );
  check(ccNoTenantRes, {
    '[-] cycle count no X-Tenant-Id → 4xx': (r) => r.status >= 400 && r.status < 500,
  });

  // Enter count on non-existent line → 404
  const ccBadLineRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/` +
      '00000000-0000-0000-0000-000000000099/lines/00000000-0000-0000-0000-000000000098/count',
    JSON.stringify({ countedQty: 10 }),
    { headers: hdrs }
  );
  check(ccBadLineRes, {
    '[-] enter count on non-existent line → 404': (r) => r.status === 404,
  });

  // ── Gap #11: Lot Genealogy — positive checks ──────────────────────────────

  // We need two batch ids: recRes already gave us a batch — receive a second one
  let parentBatchId = null;
  let childBatchId = null;
  try { parentBatchId = JSON.parse(recRes.body).data.id; } catch (_) {}

  const recRes2 = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      qty: 10,
      batchNo: `CHILD-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  try { childBatchId = JSON.parse(recRes2.body).data.id; } catch (_) {}

  // Create a SPLIT genealogy link (parent → child)
  const lgCreateRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '00000000-0000-0000-0000-000000000001',
      childBatchId: childBatchId || '00000000-0000-0000-0000-000000000002',
      qty: 10,
      relationType: 'SPLIT',
      notes: 'k6 split test',
    }),
    { headers: hdrs }
  );
  check(lgCreateRes, {
    '[+] create lot genealogy link 201': (r) => r.status === 201,
    '[+] lot link has id': (r) => {
      try { return JSON.parse(r.body).data.id !== undefined; } catch { return false; }
    },
    '[+] lot link relationType is SPLIT': (r) => {
      try { return JSON.parse(r.body).data.relationType === 'SPLIT'; } catch { return false; }
    },
  });

  // Receive a third batch so MERGE doesn't create a cycle
  const recRes3 = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '00000000-0000-0000-0000-000000000001',
      variantId,
      qty: 5,
      batchNo: `MERGE-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  let mergeBatchId = null;
  try { mergeBatchId = JSON.parse(recRes3.body).data.id; } catch (_) {}

  // Create a MERGE link: childBatchId + mergeBatchId → new merged batch (use parentBatchId as target)
  const lgMergeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: mergeBatchId || '00000000-0000-0000-0000-000000000003',
      childBatchId: childBatchId || '00000000-0000-0000-0000-000000000002',
      qty: 5,
      relationType: 'MERGE',
    }),
    { headers: hdrs }
  );
  check(lgMergeRes, {
    '[+] create MERGE lot link 201': (r) => r.status === 201,
  });

  // Get ancestors of childBatchId
  if (childBatchId) {
    const lgAncestorsRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${childBatchId}/ancestors`,
      { headers: hdrs }
    );
    check(lgAncestorsRes, {
      '[+] get lot ancestors 200': (r) => r.status === 200,
      '[+] ancestors list is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data.ancestors); } catch { return false; }
      },
    });

    // Get descendants of parentBatchId
    const lgDescRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${parentBatchId}/descendants`,
      { headers: hdrs }
    );
    check(lgDescRes, {
      '[+] get lot descendants 200': (r) => r.status === 200,
      '[+] descendants list is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data.descendants); } catch { return false; }
      },
    });

    // Get direct links for childBatchId
    const lgLinksRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${childBatchId}/links`,
      { headers: hdrs }
    );
    check(lgLinksRes, {
      '[+] get direct lot links 200': (r) => r.status === 200,
      '[+] direct links is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data); } catch { return false; }
      },
    });
  }

  // Idempotent re-create returns conflict (409 / 4xx) — same parent+child pair
  if (parentBatchId && childBatchId) {
    const lgDupRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
      JSON.stringify({ parentBatchId, childBatchId, qty: 10, relationType: 'SPLIT' }),
      { headers: hdrs }
    );
    check(lgDupRes, {
      '[+] duplicate lot link returns 4xx (idempotent guard)': (r) => r.status >= 400 && r.status < 500,
    });
  }

  // ── Gap #11: Lot Genealogy — negative checks ──────────────────────────────

  // Missing parentBatchId
  const lgNoParentRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({ childBatchId: childBatchId || '00000000-0000-0000-0000-000000000002', qty: 5,
      relationType: 'SPLIT' }),
    { headers: hdrs }
  );
  check(lgNoParentRes, {
    '[-] lot link missing parentBatchId → 400': (r) => r.status === 400,
  });

  // Missing childBatchId
  const lgNoChildRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({ parentBatchId: parentBatchId || '00000000-0000-0000-0000-000000000001',
      qty: 5, relationType: 'SPLIT' }),
    { headers: hdrs }
  );
  check(lgNoChildRes, {
    '[-] lot link missing childBatchId → 400': (r) => r.status === 400,
  });

  // Missing qty
  const lgNoQtyRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '00000000-0000-0000-0000-000000000001',
      childBatchId: mergeBatchId || '00000000-0000-0000-0000-000000000003',
      relationType: 'SPLIT',
    }),
    { headers: hdrs }
  );
  check(lgNoQtyRes, {
    '[-] lot link missing qty → 400': (r) => r.status === 400,
  });

  // Invalid relationType
  const lgBadTypeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '00000000-0000-0000-0000-000000000001',
      childBatchId: mergeBatchId || '00000000-0000-0000-0000-000000000003',
      qty: 5,
      relationType: 'INVALID',
    }),
    { headers: hdrs }
  );
  check(lgBadTypeRes, {
    '[-] lot link invalid relationType → 400': (r) => r.status === 400,
  });

  // No tenant header → 4xx
  const lgNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: '00000000-0000-0000-0000-000000000001',
      childBatchId: '00000000-0000-0000-0000-000000000002',
      qty: 5,
      relationType: 'SPLIT',
    }),
    { headers: JSON_CT }
  );
  check(lgNoTenantRes, {
    '[-] lot genealogy no X-Tenant-Id → 4xx': (r) => r.status >= 400 && r.status < 500,
  });
}
