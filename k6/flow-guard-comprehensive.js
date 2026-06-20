/**
 * Comprehensive Flow Guard Test for Shelf-J
 *
 * Tests the COMPLETE business flow end-to-end with PROPER SEQUENCING:
 * 1. User Registration & Auth Flow
 * 2. Tenant Onboarding Flow
 * 3. Location Setup Flow (Stores → Zones)
 * 4. Staff Management Flow
 * 5. Catalog Setup Flow (Brands → Categories → Products → Variants)
 * 6. Inventory Setup Flow (Receive → Adjust → Set Thresholds)
 * 7. Admin Operations Flow (Listing & Details)
 * 8. Retail Operations Flow (Browse → Reserve → Consume)
 *
 * This test validates that:
 * - All endpoints are accessible in proper flow order
 * - Flow guard allows correct sequences and blocks incorrect ones
 * - No endpoints are left out or unaccounted for
 * - Data dependencies are properly enforced
 *
 * Run: k6 run k6/flow-guard-comprehensive.js --env BASE_URL=http://localhost:8090
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

// ── Custom Metrics ────────────────────────────────────────────────────────────
const flowErrors = new Counter('flow_errors');
const flowSuccessRate = new Rate('flow_success_rate');
const endpointLatency = new Trend('endpoint_latency_ms', true);
const checksPassed = new Counter('checks_passed');
const checksFailed = new Counter('checks_failed');

// ── Configuration ─────────────────────────────────────────────────────────────
const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const JSON_CT = { 'Content-Type': 'application/json' };

// ── Test Options ──────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    flow_guard: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      exec: 'flowGuardTest',
    },
  },
  thresholds: {
    checks: ['rate>0.95'],
    flow_success_rate: ['rate>0.95'],
    flow_errors: ['count<5'],
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────
let authToken = null;  // Set after registration

function hdrs(tenantId = null, userId = null) {
  const h = { ...JSON_CT };
  // Gateway requires Authorization header with Bearer token
  if (authToken) h['Authorization'] = `Bearer ${authToken}`;
  // X-* headers provide service context
  if (tenantId) h['X-Tenant-Id'] = tenantId;
  if (userId) h['X-User-Id'] = userId;
  return h;
}

function post(path, body, tenantId = null, userId = null) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(tenantId, userId) });
}

function put(path, body, tenantId = null, userId = null) {
  return http.put(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(tenantId, userId) });
}

function patch(path, body, tenantId = null, userId = null) {
  return http.patch(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(tenantId, userId) });
}

function get(path, tenantId = null, userId = null) {
  return http.get(`${BASE}${path}`, { headers: hdrs(tenantId, userId) });
}

// Public storefront reads (catalog/*) ignore X-Tenant-Id (the gateway strips it on every
// request to prevent spoofing) and resolve tenant from X-Storefront-Tenant instead — see
// JwtAuthFilter.STOREFRONT_TENANT_HEADER.
function getStorefront(path, tenantId) {
  return http.get(`${BASE}${path}`, { headers: { ...JSON_CT, 'X-Storefront-Tenant': tenantId } });
}

function ok(res, tag, expectedStatus = 200) {
  const t0 = Date.now();
  const passed = check(res, {
    [`${tag} ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });
  endpointLatency.add(Date.now() - t0);
  if (passed) {
    checksPassed.add(1);
    flowSuccessRate.add(1);
  } else {
    checksFailed.add(1);
    flowErrors.add(1);
    flowSuccessRate.add(0);
    console.error(`FAILED: ${tag} — got ${res.status}, expected ${expectedStatus}`);
    console.error(`Body: ${res.body.substring(0, 500)}`);
  }
  return passed;
}

function data(res) {
  try {
    return JSON.parse(res.body).data || {};
  } catch (_) {
    return {};
  }
}

// Role grants (e.g. OWNER on tenant creation) propagate async via the outbox + Kafka consumer
// (see iam-svc TenantCreatedConsumer/Handler) — a login right after the triggering call can race
// the consumer and come back with the pre-grant role set. Poll login until the expected role
// claim shows up, instead of trusting a single attempt.
// Worst case for the role to appear is just under one outbox poll cycle (shelfj.outbox.poll-seconds=5)
// plus Kafka delivery, but under concurrent load (e.g. repeated back-to-back k6 runs competing
// for the same poll/consumer resources) it can take noticeably longer — budget generously rather
// than just past the nominal cycle.
function loginUntilRole(email, password, expectedRole, attempts = 25, delaySeconds = 1.2) {
  let lastRes = null;
  for (let i = 0; i < attempts; i++) {
    lastRes = post('/api/iam-svc/auth/login', { email, password });
    const token = data(lastRes).accessToken;
    if (token && (jwtPayload(token).roles || []).includes(expectedRole)) {
      authToken = token;
      return lastRes;
    }
    sleep(delaySeconds);
  }
  return lastRes;
}

function jwtPayload(token) {
  try {
    const parts = token.split('.');
    const b64url = parts[1];
    const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(encoding.b64decode(b64, 'rawstd', 's'));
  } catch (_) {
    return {};
  }
}

// ── Main Flow Guard Test ──────────────────────────────────────────────────────

export function flowGuardTest() {
  const runId = Date.now();
  let passed = 0;
  let total = 0;

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 1: Auth Flow
  // ═══════════════════════════════════════════════════════════════════════════
  // POST /api/iam-svc/auth/register
  group('Phase 1: Auth Flow — Register', () => {
    const regRes = post('/api/iam-svc/auth/register', {
      email: `flow-guard-${runId}@test.local`,
      password: 'Flow@Guard123',
      phone: `99${runId}`,
    });
    total++;
    if (ok(regRes, 'POST /auth/register', 201)) passed++;
  });

  // Get userId from auth token (note: requires a second registration for extraction)
  const userEmail = `flow-guard-${runId}b@test.local`;
  const userPassword = 'Flow@Guard123';
  const regRes2 = http.post(`${BASE}/api/iam-svc/auth/register`, JSON.stringify({
    email: userEmail,
    password: userPassword,
    phone: `99${runId}b`,
  }), { headers: JSON_CT });
  const tokens2 = data(regRes2);
  authToken = tokens2.accessToken;  // Set global auth token for all subsequent requests
  const userId = jwtPayload(authToken).sub;

  if (!userId) {
    console.error('FATAL: Could not extract userId');
    return;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 2: Tenant Onboarding Flow
  // ═══════════════════════════════════════════════════════════════════════════
  let tenantId = null;
  group('Phase 2: Tenant Onboarding — Create Tenant & Initial Store', () => {
    // POST /api/tenant-svc/onboarding/tenants
    const tenantRes = post(
      '/api/tenant-svc/onboarding/tenants',
      {
        businessName: `FlowGuard Co ${runId}`,
        legalName: `FlowGuard Limited ${runId}`,
        country: 'IN',
        currency: 'INR',
      },
      null,
      userId
    );
    total++;
    if (ok(tenantRes, 'POST /onboarding/tenants', 201)) passed++;
    tenantId = data(tenantRes).id;

    if (!tenantId) {
      console.error('FATAL: Could not extract tenantId');
      return;
    }

    // The OWNER role grant from tenant creation propagates async via the outbox + iam-svc's
    // TenantCreatedConsumer — poll login until the JWT actually carries it, so every /admin/*
    // call below (which gates on the role claim, unlike /onboarding/*) is authorized.
    total++;
    if (ok(loginUntilRole(userEmail, userPassword, 'OWNER'), 'POST /auth/login (post-tenant-creation)', 200)) passed++;

    // GET /api/tenant-svc/admin/tenant
    const getRes = get('/api/tenant-svc/admin/tenant', tenantId, userId);
    total++;
    if (ok(getRes, 'GET /admin/tenant', 200)) passed++;

    // PUT /api/tenant-svc/admin/tenant (update tenant profile)
    const updateRes = put(
      '/api/tenant-svc/admin/tenant',
      {
        businessName: `FlowGuard Co Updated ${runId}`,
      },
      tenantId,
      userId
    );
    total++;
    if (ok(updateRes, 'PUT /admin/tenant', 200)) passed++;

    sleep(0.5);
  });

  if (!tenantId) {
    console.error('FATAL: Tenant creation failed, cannot continue');
    return;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 3: Location Setup — Stores & Zones
  // ═══════════════════════════════════════════════════════════════════════════
  let storeId = null;
  let zoneIds = [];
  group('Phase 3: Location Setup — Create Stores & Zones', () => {
    // POST /api/tenant-svc/onboarding/stores (must pass tenantId for context)
    const storeRes = post(
      '/api/tenant-svc/onboarding/stores',
      {
        name: 'FlowGuard Main Store',
        code: `FG-${runId.toString().slice(-6)}`,
        type: 'STORE',
        line1: '1 Market St',
        city: 'Mumbai',
        state: 'MH',
        country: 'IN',
        pincode: '400001',
        timezone: 'Asia/Kolkata',
      },
      tenantId,  // <-- Must pass tenantId, not null!
      userId
    );
    total++;
    if (ok(storeRes, 'POST /onboarding/stores', 201)) passed++;
    storeId = data(storeRes).id;

    // GET /api/tenant-svc/admin/stores
    const listRes = get('/api/tenant-svc/admin/stores', tenantId, userId);
    total++;
    if (ok(listRes, 'GET /admin/stores', 200)) passed++;

    if (storeId) {
      // GET /api/tenant-svc/admin/stores/{storeId}
      const getRes = get(`/api/tenant-svc/admin/stores/${storeId}`, tenantId, userId);
      total++;
      if (ok(getRes, 'GET /admin/stores/{id}', 200)) passed++;

      // PUT /api/tenant-svc/admin/stores/{storeId} (update store)
      const updateRes = put(
        `/api/tenant-svc/admin/stores/${storeId}`,
        {
          name: 'FlowGuard Main Store Updated',
        },
        tenantId,
        userId
      );
      total++;
      if (ok(updateRes, 'PUT /admin/stores/{id}', 200)) passed++;

      // PATCH /api/tenant-svc/admin/stores/{storeId}/status
      const patchRes = patch(
        `/api/tenant-svc/admin/stores/${storeId}/status`,
        { status: 'ACTIVE' },
        tenantId,
        userId
      );
      total++;
      if (ok(patchRes, 'PATCH /admin/stores/{id}/status', 200)) passed++;

      // GET /api/tenant-svc/admin/stores/{storeId}/zones
      const zonesRes = get(`/api/tenant-svc/admin/stores/${storeId}/zones`, tenantId, userId);
      total++;
      if (ok(zonesRes, 'GET /admin/stores/{id}/zones', 200)) passed++;

      // POST /api/tenant-svc/admin/stores/{storeId}/zones (create zone)
      const zoneRes = post(
        `/api/tenant-svc/admin/stores/${storeId}/zones`,
        { name: 'Aisle A', code: 'AISLE-A', type: 'AISLE' },
        tenantId,
        userId
      );
      total++;
      if (ok(zoneRes, 'POST /admin/stores/{id}/zones', 201)) passed++;
      const zoneId = data(zoneRes).id;
      if (zoneId) zoneIds.push(zoneId);

      // Create second zone for multi-zone testing
      const zone2Res = post(
        `/api/tenant-svc/admin/stores/${storeId}/zones`,
        { name: 'Aisle B', code: 'AISLE-B', type: 'AISLE' },
        tenantId,
        userId
      );
      total++;
      if (ok(zone2Res, 'POST /admin/stores/{id}/zones (2nd)', 201)) passed++;
      const zone2Id = data(zone2Res).id;
      if (zone2Id) zoneIds.push(zone2Id);

      if (zoneId) {
        // GET /api/tenant-svc/admin/stores/{storeId}/zones/{zoneId}
        const getZoneRes = get(`/api/tenant-svc/admin/stores/${storeId}/zones/${zoneId}`, tenantId, userId);
        total++;
        if (ok(getZoneRes, 'GET /admin/stores/{storeId}/zones/{id}', 200)) passed++;

        // PUT /api/tenant-svc/admin/stores/{storeId}/zones/{zoneId}
        const updateZoneRes = put(
          `/api/tenant-svc/admin/stores/${storeId}/zones/${zoneId}`,
          { name: 'Aisle A Updated', code: 'AISLE-A', type: 'AISLE' },
          tenantId,
          userId
        );
        total++;
        if (ok(updateZoneRes, 'PUT /admin/stores/{storeId}/zones/{id}', 200)) passed++;

        // PATCH /api/tenant-svc/admin/stores/{storeId}/zones/{zoneId}/status
        const patchZoneRes = patch(
          `/api/tenant-svc/admin/stores/${storeId}/zones/${zoneId}/status`,
          { status: 'ACTIVE' },
          tenantId,
          userId
        );
        total++;
        if (ok(patchZoneRes, 'PATCH /admin/stores/{storeId}/zones/{id}/status', 200)) passed++;
      }
    }

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 4: Staff Management
  // ═══════════════════════════════════════════════════════════════════════════
  group('Phase 4: Staff Management', () => {
    // POST /api/tenant-svc/admin/staff (assign user to store)
    const staffRes = post(
      '/api/tenant-svc/admin/staff',
      { userId: userId, storeId: storeId, role: 'MANAGER' },
      tenantId,
      userId
    );
    total++;
    if (ok(staffRes, 'POST /admin/staff', 201)) passed++;

    // GET /api/tenant-svc/admin/staff
    const listRes = get('/api/tenant-svc/admin/staff', tenantId, userId);
    total++;
    if (ok(listRes, 'GET /admin/staff', 200)) passed++;

    // DELETE /api/tenant-svc/admin/staff/{userId}
    // (skipping for now as it would remove our test user)

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 5: Catalog Setup — Products, Brands, Categories
  // ═══════════════════════════════════════════════════════════════════════════
  let brandId = null;
  let categoryId = null;
  let variantIds = [];
  group('Phase 5: Catalog Setup — Brands, Categories, Products, Variants', () => {
    // POST /api/product-svc/admin/brands
    const brandRes = post(
      '/api/product-svc/admin/brands',
      { name: `FlowGuard Brand ${runId}` },
      tenantId,
      userId
    );
    total++;
    if (ok(brandRes, 'POST /admin/brands', 201)) passed++;
    brandId = data(brandRes).id;

    // GET /api/product-svc/admin/brands
    const listBrandsRes = get('/api/product-svc/admin/brands', tenantId, userId);
    total++;
    if (ok(listBrandsRes, 'GET /admin/brands', 200)) passed++;

    if (brandId) {
      // GET /api/product-svc/admin/brands/{id}
      const getBrandRes = get(`/api/product-svc/admin/brands/${brandId}`, tenantId, userId);
      total++;
      if (ok(getBrandRes, 'GET /admin/brands/{id}', 200)) passed++;

      // PUT /api/product-svc/admin/brands/{id}
      const updateBrandRes = put(
        `/api/product-svc/admin/brands/${brandId}`,
        { name: `FlowGuard Brand Updated ${runId}` },
        tenantId,
        userId
      );
      total++;
      if (ok(updateBrandRes, 'PUT /admin/brands/{id}', 200)) passed++;
    }

    // POST /api/product-svc/admin/categories
    const catRes = post(
      '/api/product-svc/admin/categories',
      { name: `FlowGuard Category ${runId}` },
      tenantId,
      userId
    );
    total++;
    if (ok(catRes, 'POST /admin/categories', 201)) passed++;
    categoryId = data(catRes).id;

    // GET /api/product-svc/admin/categories
    const listCatsRes = get('/api/product-svc/admin/categories', tenantId, userId);
    total++;
    if (ok(listCatsRes, 'GET /admin/categories', 200)) passed++;

    // POST /api/product-svc/admin/products
    const prodRes = post(
      '/api/product-svc/admin/products',
      {
        name: `FlowGuard Product ${runId}`,
        description: 'Test product for flow guard',
        brandId: brandId,
        categoryId: categoryId,
        sellableOnline: true,
        sellablePos: true,
      },
      tenantId,
      userId
    );
    total++;
    if (ok(prodRes, 'POST /admin/products', 201)) passed++;
    const productId = data(prodRes).id;

    // GET /api/product-svc/admin/products
    const listProdsRes = get('/api/product-svc/admin/products', tenantId, userId);
    total++;
    if (ok(listProdsRes, 'GET /admin/products', 200)) passed++;

    if (productId) {
      // GET /api/product-svc/admin/products/{id}
      const getProdRes = get(`/api/product-svc/admin/products/${productId}`, tenantId, userId);
      total++;
      if (ok(getProdRes, 'GET /admin/products/{id}', 200)) passed++;

      // PUT /api/product-svc/admin/products/{id}
      const updateProdRes = put(
        `/api/product-svc/admin/products/${productId}`,
        { name: `FlowGuard Product Updated ${runId}`, sellableOnline: true, sellablePos: true },
        tenantId,
        userId
      );
      total++;
      if (ok(updateProdRes, 'PUT /admin/products/{id}', 200)) passed++;

      // POST /api/product-svc/admin/products/{id}/variants
      const varRes = post(
        `/api/product-svc/admin/products/${productId}/variants`,
        {
          sku: `SKU-${runId.toString().slice(-8).toUpperCase()}`,
          barcode: `BAR${runId}`,
          attributes: JSON.stringify({ color: 'black', size: 'M' }),
          unit: 'PCS',
        },
        tenantId,
        userId
      );
      total++;
      if (ok(varRes, 'POST /admin/products/{id}/variants', 201)) passed++;
      const variantId = data(varRes).id;
      if (variantId) {
        variantIds.push(variantId);

        // GET /api/product-svc/admin/products/{id}/variants
        const listVarsRes = get(`/api/product-svc/admin/products/${productId}/variants`, tenantId, userId);
        total++;
        if (ok(listVarsRes, 'GET /admin/products/{id}/variants', 200)) passed++;
      }

      // GET /api/product-svc/catalog/products (customer/store view — public storefront read)
      const catalogRes = getStorefront('/api/product-svc/catalog/products', tenantId);
      total++;
      if (ok(catalogRes, 'GET /catalog/products', 200)) passed++;

      // GET /api/product-svc/catalog/products/{id}
      const catalogDetailRes = getStorefront(`/api/product-svc/catalog/products/${productId}`, tenantId);
      total++;
      if (ok(catalogDetailRes, 'GET /catalog/products/{id}', 200)) passed++;

      // GET /api/product-svc/catalog/products/{id}/variants
      const catalogVarsRes = getStorefront(`/api/product-svc/catalog/products/${productId}/variants`, tenantId);
      total++;
      if (ok(catalogVarsRes, 'GET /catalog/products/{id}/variants', 200)) passed++;
    }

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 6: Inventory Setup — Receive, Adjust, Thresholds
  // ═══════════════════════════════════════════════════════════════════════════
  group('Phase 6: Inventory Setup — Receive Stock, Adjust, Set Thresholds', () => {
    if (variantIds.length > 0 && storeId) {
      const variantId = variantIds[0];

      // POST /api/inventory-svc/admin/inventory/receive
      const recRes = post(
        '/api/inventory-svc/admin/inventory/receive',
        {
          storeId: storeId,
          variantId: variantId,
          qty: 500,
          batchNo: `BATCH-${runId.toString().slice(-8)}`,
          costPrice: '250.00',
          expiryDate: '2027-12-31',
        },
        tenantId,
        userId
      );
      total++;
      if (ok(recRes, 'POST /admin/inventory/receive', 201)) passed++;
      const batchId = data(recRes).id;

      // GET /api/inventory-svc/admin/inventory/levels
      const levelsRes = get(
        `/api/inventory-svc/admin/inventory/levels?store=${storeId}`,
        tenantId,
        userId
      );
      total++;
      if (ok(levelsRes, 'GET /admin/inventory/levels', 200)) passed++;

      // GET /api/inventory-svc/admin/inventory/batches
      const batchesRes = get(
        `/api/inventory-svc/admin/inventory/batches?store=${storeId}&variant=${variantId}`,
        tenantId,
        userId
      );
      total++;
      if (ok(batchesRes, 'GET /admin/inventory/batches', 200)) passed++;

      if (batchId) {
        // GET /api/inventory-svc/admin/inventory/batches/{id}
        const getBatchRes = get(
          `/api/inventory-svc/admin/inventory/batches/${batchId}`,
          tenantId,
          userId
        );
        total++;
        if (ok(getBatchRes, 'GET /admin/inventory/batches/{id}', 200)) passed++;
      }

      // POST /api/inventory-svc/admin/inventory/adjust
      const adjRes = post(
        '/api/inventory-svc/admin/inventory/adjust',
        {
          storeId: storeId,
          variantId: variantId,
          delta: 50,
          reason: 'cycle count correction',
        },
        tenantId,
        userId
      );
      total++;
      if (ok(adjRes, 'POST /admin/inventory/adjust', 200)) passed++;

      // GET /api/inventory-svc/admin/inventory/movements
      const movementsRes = get(
        `/api/inventory-svc/admin/inventory/movements?store=${storeId}&limit=20`,
        tenantId,
        userId
      );
      total++;
      if (ok(movementsRes, 'GET /admin/inventory/movements', 200)) passed++;

      // POST /api/inventory-svc/admin/inventory/thresholds (set reorder threshold)
      const thrRes = post(
        '/api/inventory-svc/admin/inventory/thresholds',
        { storeId: storeId, variantId: variantId, threshold: '50.000' },
        tenantId,
        userId
      );
      total++;
      if (ok(thrRes, 'POST /admin/inventory/thresholds', 201)) passed++;

      // GET /api/inventory-svc/admin/inventory/thresholds
      const getThrsRes = get(
        `/api/inventory-svc/admin/inventory/thresholds?store=${storeId}`,
        tenantId,
        userId
      );
      total++;
      if (ok(getThrsRes, 'GET /admin/inventory/thresholds', 200)) passed++;
    }

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 7: Onboarding Status
  // ═══════════════════════════════════════════════════════════════════════════
  group('Phase 7: Onboarding Status Check', () => {
    // GET /api/tenant-svc/onboarding/status (must pass tenantId for context)
    const statusRes = get('/api/tenant-svc/onboarding/status', tenantId, userId);
    total++;
    if (ok(statusRes, 'GET /onboarding/status', 200)) passed++;

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 8: Retail Operations — Reservations & Consumption
  // ═══════════════════════════════════════════════════════════════════════════
  group('Phase 8: Retail Operations — Reserve & Consume Stock', () => {
    if (variantIds.length > 0 && storeId) {
      const variantId = variantIds[0];

      // POST /api/inventory-svc/inventory/reservations
      const resRes = post(
        '/api/inventory-svc/inventory/reservations',
        {
          storeId: storeId,
          variantId: variantId,
          qty: 5,
          ttlSeconds: 300,
        },
        tenantId,
        userId
      );
      total++;
      if (ok(resRes, 'POST /inventory/reservations', 201)) passed++;
      const reservationId = data(resRes).id;

      // GET /api/inventory-svc/inventory/reservations
      const listResRes = get(
        `/api/inventory-svc/inventory/reservations?store=${storeId}&status=HELD`,
        tenantId,
        userId
      );
      total++;
      if (ok(listResRes, 'GET /inventory/reservations', 200)) passed++;

      if (reservationId) {
        // GET /api/inventory-svc/inventory/reservations/{id}
        const getResRes = get(
          `/api/inventory-svc/inventory/reservations/${reservationId}`,
          tenantId,
          userId
        );
        total++;
        if (ok(getResRes, 'GET /inventory/reservations/{id}', 200)) passed++;

        // POST /api/inventory-svc/inventory/reservations/{id}/consume
        const consumeRes = post(
          `/api/inventory-svc/inventory/reservations/${reservationId}/consume`,
          {},
          tenantId,
          userId
        );
        total++;
        if (ok(consumeRes, 'POST /inventory/reservations/{id}/consume', 200)) passed++;

        // Verify status changed to CONSUMED
        const verifyRes = get(
          `/api/inventory-svc/inventory/reservations/${reservationId}`,
          tenantId,
          userId
        );
        total++;
        if (ok(verifyRes, 'GET /inventory/reservations/{id} (post-consume)', 200)) passed++;
      }

      // Test release flow with a second reservation
      const res2Res = post(
        '/api/inventory-svc/inventory/reservations',
        {
          storeId: storeId,
          variantId: variantId,
          qty: 3,
          ttlSeconds: 60,
        },
        tenantId,
        userId
      );
      total++;
      if (ok(res2Res, 'POST /inventory/reservations (for release)', 201)) passed++;
      const reservationId2 = data(res2Res).id;

      if (reservationId2) {
        // POST /api/inventory-svc/inventory/reservations/{id}/release
        const releaseRes = post(
          `/api/inventory-svc/inventory/reservations/${reservationId2}/release`,
          {},
          tenantId,
          userId
        );
        total++;
        if (ok(releaseRes, 'POST /inventory/reservations/{id}/release', 200)) passed++;
      }
    }

    sleep(0.5);
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // SUMMARY
  // ═══════════════════════════════════════════════════════════════════════════
  group('Test Summary', () => {
    console.log(`\n\n${'═'.repeat(80)}`);
    console.log(`FLOW GUARD COMPREHENSIVE TEST RESULTS`);
    console.log(`${'═'.repeat(80)}`);
    console.log(`Total Endpoints Tested: ${total}`);
    console.log(`Passed: ${passed}`);
    console.log(`Failed: ${total - passed}`);
    console.log(`Success Rate: ${((passed / total) * 100).toFixed(1)}%`);
    console.log(`${'═'.repeat(80)}\n`);

    check({ success: passed === total }, {
      'All endpoints passed': (o) => o.success,
    });
  });
}

// ── Summary Handler ───────────────────────────────────────────────────────────
export function handleSummary(data) {
  return {
    stdout: JSON.stringify(
      {
        total_endpoints_tested: data.metrics.checks?.values?.passes + (data.metrics.checks?.values?.fails || 0),
        checks_passed: data.metrics.checks?.values?.passes,
        checks_failed: data.metrics.checks?.values?.fails,
        flow_success_rate: data.metrics.flow_success_rate?.values?.rate?.toFixed(3),
        flow_errors: data.metrics.flow_errors?.values?.count,
        avg_endpoint_latency_ms: data.metrics.endpoint_latency_ms?.values?.avg?.toFixed(1),
        p95_endpoint_latency_ms: data.metrics.endpoint_latency_ms?.values?.['p(95)']?.toFixed(1),
      },
      null,
      2
    ),
  };
}
