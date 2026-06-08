/**
 * Full-stack simulation for Shelf-J.
 *
 * Phase 1 (setup): seeds the entire data model via API — tenant, store, zone, staff,
 * product catalogue, and inventory batches.
 *
 * Phase 2 (scenarios): concurrent VUs simulate realistic workloads:
 *   - browseCatalog  : customers listing products / stock levels
 *   - completeSale   : reserve → consume (FIFO deduct)
 *   - abandonCart    : reserve → release
 *   - inventoryOps   : admin receive stock + adjust
 *   - adminProductOps: admin CRUD on brands / categories / products
 *
 * Run: k6 run k6/full-stack-simulation.js --env BASE_URL=http://localhost:8090
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

// ── custom metrics ────────────────────────────────────────────────────────────
const simErrors = new Counter('sim_errors');
const reserveSuccessRate = new Rate('reserve_success_rate');
const levelLatency = new Trend('level_latency_ms', true);

// ── options ───────────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    browseCatalog: {
      executor: 'constant-vus',
      vus: 3,
      duration: '30s',
      exec: 'browseCatalog',
      startTime: '5s',
    },
    completeSale: {
      executor: 'constant-vus',
      vus: 2,
      duration: '25s',
      exec: 'completeSale',
      startTime: '8s',
    },
    abandonCart: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      exec: 'abandonCart',
      startTime: '10s',
    },
    inventoryOps: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      exec: 'inventoryOps',
      startTime: '8s',
    },
    adminProductOps: {
      executor: 'constant-vus',
      vus: 1,
      duration: '20s',
      exec: 'adminProductOps',
      startTime: '5s',
    },
  },
  thresholds: {
    checks: ['rate>0.90'],
    sim_errors: ['count<10'],
    reserve_success_rate: ['rate>0.85'],
  },
};

// ── helpers ───────────────────────────────────────────────────────────────────
const BASE = __ENV.BASE_URL || 'http://localhost:8090';

function hdrs(tenantId, userId) {
  const h = { 'Content-Type': 'application/json' };
  if (tenantId) h['X-Tenant-Id'] = tenantId;
  if (userId) h['X-User-Id'] = userId;
  return h;
}

function post(path, body, tenantId, userId) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(tenantId, userId) });
}

function put(path, body, tenantId, userId) {
  return http.put(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(tenantId, userId) });
}

function get(path, tenantId, userId) {
  return http.get(`${BASE}${path}`, { headers: hdrs(tenantId, userId) });
}

function ok(res, tag) {
  const passed = check(res, { [`${tag} 2xx`]: (r) => r.status >= 200 && r.status < 300 });
  if (!passed) simErrors.add(1);
  return passed;
}

function data(res) {
  try {
    return JSON.parse(res.body).data || {};
  } catch (_) {
    return {};
  }
}

function randomSku() {
  return 'SKU-' + Math.random().toString(36).slice(2, 8).toUpperCase();
}

// Decode a JWT and return its payload as an object (no verification).
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

// ── setup: seed the entire data model once ────────────────────────────────────
export function setup() {
  // 1. Register owner user — use timestamp-based phone to avoid unique conflicts across runs
  const runId = Date.now();
  const regRes = post('/api/iam-svc/auth/register', {
    email: `sim-owner-${runId}@shelfj.test`,
    password: 'Sim@12345',
    phone: `99${runId}`,
  });
  if (!ok(regRes, 'setup: register owner')) return null;
  const tokens = data(regRes);
  const accessToken = tokens.accessToken;

  // 2. Decode userId from JWT `sub` claim (Authorization header is not forwarded by the gateway)
  const claims = jwtPayload(accessToken);
  const userId = claims.sub;
  if (!userId) {
    console.error('Could not decode userId from JWT');
    return null;
  }

  // 3. Create tenant
  const tenantRes = post(
    '/api/tenant-svc/onboarding/tenants',
    {
      businessName: 'SimRetail Ltd',
      legalName: 'SimRetail Limited',
      country: 'IN',
      currency: 'INR',
    },
    null,
    userId
  );
  if (!ok(tenantRes, 'setup: create tenant')) return null;
  const tenantId = data(tenantRes).id;

  // 4. Create default store via onboarding path (auto-creates DEFAULT zone)
  const storeRes = post(
    '/api/tenant-svc/onboarding/stores',
    {
      name: 'Sim Main Store',
      code: 'SIM01',
      type: 'STORE',
      line1: '1 Market St',
      city: 'Mumbai',
      state: 'MH',
      country: 'IN',
      pincode: '400001',
      timezone: 'Asia/Kolkata',
    },
    tenantId,
    userId
  );
  if (!ok(storeRes, 'setup: create store')) return null;
  const storeId = data(storeRes).id;

  // 5. Add an extra AISLE zone
  post(
    `/api/tenant-svc/admin/stores/${storeId}/zones`,
    { name: 'Aisle A', code: 'AISLE-A', type: 'AISLE' },
    tenantId,
    userId
  );

  // 6. Assign owner as MANAGER for the store
  post(
    '/api/tenant-svc/admin/staff',
    { userId: userId, storeId: storeId, role: 'MANAGER' },
    tenantId,
    userId
  );

  // 7. Create brand + category
  const brandRes = post('/api/product-svc/admin/brands', { name: 'SimBrand' }, tenantId, userId);
  const brandId = data(brandRes).id;

  const catRes = post(
    '/api/product-svc/admin/categories',
    { name: 'Electronics' },
    tenantId,
    userId
  );
  const categoryId = data(catRes).id;

  // 8. Create 3 products with variants and receive stock
  const variantIds = [];
  for (let p = 0; p < 3; p++) {
    const prodRes = post(
      '/api/product-svc/admin/products',
      {
        name: `SimProduct ${p + 1}`,
        description: `Simulated product ${p + 1}`,
        brandId: brandId,
        categoryId: categoryId,
        sellableOnline: true,
        sellablePos: true,
      },
      tenantId,
      userId
    );
    const productId = data(prodRes).id;
    if (!productId) continue;

    const varRes = post(
      `/api/product-svc/admin/products/${productId}/variants`,
      {
        sku: randomSku(),
        barcode: `BAR${Date.now()}${p}`,
        attributes: JSON.stringify({ color: 'black', size: 'M' }),
        unit: 'PCS',
      },
      tenantId,
      userId
    );
    const variantId = data(varRes).id;
    if (!variantId) continue;
    variantIds.push(variantId);

    // Receive initial stock
    post(
      '/api/inventory-svc/admin/inventory/receive',
      {
        storeId: storeId,
        variantId: variantId,
        qty: 200,
        batchNo: `BATCH-${variantId.slice(0, 8)}`,
        costPrice: '250.00',
        expiryDate: '2027-12-31',
      },
      tenantId,
      userId
    );

    // Set reorder threshold
    post(
      '/api/inventory-svc/admin/inventory/thresholds',
      { storeId: storeId, variantId: variantId, threshold: '20.000' },
      tenantId,
      userId
    );
  }

  console.log(
    `Setup: tenantId=${tenantId} storeId=${storeId} userId=${userId} variants=${variantIds.length} brandId=${brandId} categoryId=${categoryId}`
  );
  return { tenantId, userId, storeId, brandId, categoryId, variantIds };
}

// ── scenario: browse catalogue (read path) ────────────────────────────────────
export function browseCatalog(d) {
  if (!d) return;
  const { tenantId, userId, storeId, variantIds } = d;

  let res = get('/api/product-svc/catalog/products', tenantId, userId);
  ok(res, 'list products');

  const t0 = Date.now();
  res = get(`/api/inventory-svc/admin/inventory/levels?store=${storeId}`, tenantId, userId);
  levelLatency.add(Date.now() - t0);
  ok(res, 'get levels');

  const vid = variantIds[Math.floor(Math.random() * variantIds.length)];
  res = get(
    `/api/inventory-svc/admin/inventory/batches?store=${storeId}&variant=${vid}`,
    tenantId,
    userId
  );
  ok(res, 'list batches');

  res = get(
    `/api/inventory-svc/admin/inventory/movements?store=${storeId}&limit=10`,
    tenantId,
    userId
  );
  ok(res, 'list movements');

  sleep(1);
}

// ── scenario: complete sale (reserve → consume) ───────────────────────────────
export function completeSale(d) {
  if (!d) return;
  const { tenantId, userId, storeId, variantIds } = d;

  const variantId = variantIds[Math.floor(Math.random() * variantIds.length)];

  const rRes = post(
    '/api/inventory-svc/inventory/reservations',
    { storeId: storeId, variantId: variantId, qty: 1, ttlSeconds: 300 },
    tenantId,
    userId
  );
  const rOk = ok(rRes, 'reserve stock');
  reserveSuccessRate.add(rOk ? 1 : 0);
  if (!rOk) {
    sleep(0.5);
    return;
  }
  const reservationId = data(rRes).id;
  if (!reservationId) return;

  const cRes = post(
    `/api/inventory-svc/inventory/reservations/${reservationId}/consume`,
    {},
    tenantId,
    userId
  );
  ok(cRes, 'consume reservation');

  const getRes = get(
    `/api/inventory-svc/inventory/reservations/${reservationId}`,
    tenantId,
    userId
  );
  check(getRes, {
    'reservation status is CONSUMED': (r) => {
      try {
        return JSON.parse(r.body).data?.status === 'CONSUMED';
      } catch (_) {
        return false;
      }
    },
  });

  sleep(0.5);
}

// ── scenario: abandon cart (reserve → release) ────────────────────────────────
export function abandonCart(d) {
  if (!d) return;
  const { tenantId, userId, storeId, variantIds } = d;

  const variantId = variantIds[0];

  const rRes = post(
    '/api/inventory-svc/inventory/reservations',
    { storeId: storeId, variantId: variantId, qty: 1, ttlSeconds: 60 },
    tenantId,
    userId
  );
  const rOk = ok(rRes, 'reserve for abandon');
  reserveSuccessRate.add(rOk ? 1 : 0);
  if (!rOk) {
    sleep(1);
    return;
  }
  const reservationId = data(rRes).id;
  if (!reservationId) {
    sleep(1);
    return;
  }

  sleep(0.3);

  const listRes = get(
    `/api/inventory-svc/inventory/reservations?store=${storeId}&status=HELD&limit=5`,
    tenantId,
    userId
  );
  ok(listRes, 'list HELD reservations');

  const relRes = post(
    `/api/inventory-svc/inventory/reservations/${reservationId}/release`,
    {},
    tenantId,
    userId
  );
  ok(relRes, 'release reservation');

  sleep(1);
}

// ── scenario: inventory admin ops ─────────────────────────────────────────────
export function inventoryOps(d) {
  if (!d) return;
  const { tenantId, userId, storeId, variantIds } = d;

  const variantId = variantIds[Math.floor(Math.random() * variantIds.length)];

  const recRes = post(
    '/api/inventory-svc/admin/inventory/receive',
    {
      storeId: storeId,
      variantId: variantId,
      qty: 10,
      batchNo: `TOP-${Math.random().toString(36).slice(2, 6)}`,
      costPrice: '260.00',
    },
    tenantId,
    userId
  );
  ok(recRes, 'receive stock (ops)');

  const adjRes = post(
    '/api/inventory-svc/admin/inventory/adjust',
    { storeId: storeId, variantId: variantId, delta: 5, reason: 'cycle count' },
    tenantId,
    userId
  );
  ok(adjRes, 'adjust stock +5');

  const thrRes = get(
    `/api/inventory-svc/admin/inventory/thresholds?store=${storeId}`,
    tenantId,
    userId
  );
  ok(thrRes, 'list thresholds');

  post(
    '/api/inventory-svc/admin/inventory/thresholds',
    { storeId: storeId, variantId: variantId, threshold: '25.000' },
    tenantId,
    userId
  );

  sleep(1);
}

// ── scenario: admin product CRUD ──────────────────────────────────────────────
export function adminProductOps(d) {
  if (!d) return;
  const { tenantId, userId, storeId, brandId } = d;

  let res = get('/api/product-svc/admin/brands', tenantId, userId);
  ok(res, 'list brands');

  if (brandId) {
    res = put(
      `/api/product-svc/admin/brands/${brandId}`,
      { name: `SimBrand-${Date.now()}` },
      tenantId,
      userId
    );
    ok(res, 'update brand');

    res = get(`/api/product-svc/admin/brands/${brandId}`, tenantId, userId);
    ok(res, 'get brand');
  }

  res = get('/api/product-svc/admin/categories', tenantId, userId);
  ok(res, 'list categories');

  res = get('/api/product-svc/admin/products', tenantId, userId);
  ok(res, 'admin list products');

  res = get(`/api/inventory-svc/admin/inventory/batches?store=${storeId}`, tenantId, userId);
  ok(res, 'list batches (admin)');

  res = get('/api/tenant-svc/admin/tenant', tenantId, userId);
  ok(res, 'get tenant profile');

  res = get('/api/tenant-svc/admin/staff', tenantId, userId);
  ok(res, 'list staff');

  sleep(1.5);
}

// ── default (required by k6) ──────────────────────────────────────────────────
export default function () {}

// ── summary ───────────────────────────────────────────────────────────────────
export function handleSummary(d) {
  return {
    stdout: JSON.stringify(
      {
        checks_passed: d.metrics.checks?.values?.passes,
        checks_failed: d.metrics.checks?.values?.fails,
        sim_errors: d.metrics.sim_errors?.values?.count,
        reserve_success_rate: d.metrics.reserve_success_rate?.values?.rate?.toFixed(3),
        level_latency_p95_ms: d.metrics.level_latency_ms?.values?.['p(95)']?.toFixed(1),
      },
      null,
      2
    ),
  };
}
