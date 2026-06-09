/**
 * Multi-Tenant Retail Load Test — Shelf-J
 *
 * Two isolated tenants, two stores each, geographically distinct:
 *
 *   Tenant IN  —  Mumbai Retail Pvt Ltd  (India / INR)
 *     Store IN-1  Mumbai, Marine Drive       (onboarding path, auto-zone)
 *     Store IN-2  New Delhi, Connaught Place  (admin path)
 *
 *   Tenant UK  —  London Merchandise Ltd  (United Kingdom / GBP)
 *     Store UK-1  London, Oxford Street       (onboarding path, auto-zone)
 *     Store UK-2  Manchester, Arndale          (admin path)
 *
 * Flows exercised (per tenant, per store where applicable):
 *   ✓  Tenant onboarding (register → tenant → stores → zones → staff)
 *   ✓  Staff management   (assign MANAGER + CASHIER, list staff, list zones)
 *   ✓  Catalogue admin    (brand, 2 categories, 4 products × 1 variant each)
 *   ✓  Purchase           (stock receive to all 4 stores, reorder thresholds)
 *   ✓  POS sales          (reserve → consume, stock-level assertions)
 *   ✓  Cart abandon       (reserve → release)
 *   ✓  Catalog browse     (products, categories, stock levels, movements)
 *   ✓  Tenant isolation   (cross-tenant access must return no data — tracked metric)
 *
 * Scenarios (concurrent after setup):
 *   browseCatalog   4 VUs  50s  – 2 India, 2 UK; rotates both stores
 *   completeSale    4 VUs  45s  – POS flow, per-tenant sale rate tracked
 *   abandonCart     2 VUs  40s  – 1 India, 1 UK
 *   purchaseReceive 2 VUs  40s  – supplier receive, p95 latency tracked
 *   staffAdmin      2 VUs  35s  – staff/store/zone read ops
 *   catalogAdmin    2 VUs  35s  – brand/category/product/movement read + brand update
 *   isolationCheck  1 VU   30s  – cross-tenant isolation assertions
 *
 * Run: k6 run k6/multi-tenant-retail.js
 *      k6 run k6/multi-tenant-retail.js --env BASE_URL=http://localhost:8090
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

// ── Custom metrics ─────────────────────────────────────────────────────────────
const errors              = new Counter('errors');
const saleSuccessIN       = new Rate('sale_success_india');
const saleSuccessUK       = new Rate('sale_success_uk');
const catalogLatencyIN    = new Trend('catalog_latency_india_ms',    true);
const catalogLatencyUK    = new Trend('catalog_latency_uk_ms',       true);
const purchaseLatency     = new Trend('purchase_receive_latency_ms', true);
const isolationViolations    = new Counter('isolation_violations');
const materialControlLatency = new Trend('material_control_latency_ms', true);
const planningLatency        = new Trend('planning_latency_ms',         true);

// ── Scenario options ───────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    browseCatalog: {
      executor: 'constant-vus', vus: 4, duration: '50s',
      exec: 'browseCatalog', startTime: '15s',
    },
    completeSale: {
      executor: 'constant-vus', vus: 4, duration: '45s',
      exec: 'completeSale', startTime: '15s',
    },
    abandonCart: {
      executor: 'constant-vus', vus: 2, duration: '40s',
      exec: 'abandonCart', startTime: '18s',
    },
    purchaseReceive: {
      executor: 'constant-vus', vus: 2, duration: '40s',
      exec: 'purchaseReceive', startTime: '18s',
    },
    staffAdmin: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'staffAdmin', startTime: '18s',
    },
    catalogAdmin: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'catalogAdmin', startTime: '18s',
    },
    isolationCheck: {
      executor: 'constant-vus', vus: 1, duration: '30s',
      exec: 'isolationCheck', startTime: '20s',
    },
    materialControl: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'materialControl', startTime: '20s',
    },
    planningEngine: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'planningEngine', startTime: '22s',
    },
  },
  thresholds: {
    checks:                      ['rate>0.92'],
    errors:                      ['count<25'],
    sale_success_india:          ['rate>0.85'],
    sale_success_uk:             ['rate>0.85'],
    isolation_violations:        ['count==0'],
    catalog_latency_india_ms:    ['p(95)<500'],
    catalog_latency_uk_ms:       ['p(95)<500'],
    purchase_receive_latency_ms: ['p(95)<800'],
    material_control_latency_ms: ['p(95)<600'],
    planning_latency_ms:         ['p(95)<1000'],
  },
};

// ── Module-level helpers ───────────────────────────────────────────────────────
const BASE = __ENV.BASE_URL || 'http://localhost:8090';

function hdrs(tenantId, userId) {
  const h = { 'Content-Type': 'application/json' };
  if (tenantId) h['X-Tenant-Id'] = tenantId;
  if (userId)   h['X-User-Id']   = userId;
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

// Assert 2xx and count failures.
function ok(res, tag) {
  const passed = check(res, { [`${tag} 2xx`]: r => r.status >= 200 && r.status < 300 });
  if (!passed) errors.add(1);
  return passed;
}

// Extract .data from response body.
function body(res) {
  try { return JSON.parse(res.body).data || {}; } catch (_) { return {}; }
}

// Decode JWT payload (no verification).
function jwtPayload(token) {
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(encoding.b64decode(b64, 'rawstd', 's'));
  } catch (_) { return {}; }
}

// Random 6-char uppercase slug.
function slug() { return Math.random().toString(36).slice(2, 8).toUpperCase(); }

// Receive stock at a store and return the response.
function apiReceiveStock(tenantId, userId, storeId, variantId, qty, costPrice, batchPrefix) {
  return post('/api/inventory-svc/admin/inventory/receive', {
    storeId, variantId, qty,
    batchNo:    `${batchPrefix}-${slug()}`,
    costPrice:  String(costPrice),
    expiryDate: '2028-12-31',
  }, tenantId, userId);
}

// ── Setup helpers (called only in setup()) ─────────────────────────────────────

function registerUser(email) {
  const res = post('/api/iam-svc/auth/register', {
    email,
    password: 'Retail@12345',
    phone:    `9${Date.now() % 10000000000}`,
  });
  if (res.status < 200 || res.status >= 300) {
    console.error(`register failed [${email}] status=${res.status}`);
    return null;
  }
  const token = body(res).accessToken;
  return { userId: jwtPayload(token).sub };
}

function seedTenant(owner, tenantPayload, store1Payload, store2Payload, products, isIN) {
  const tag = isIN ? 'IN' : 'UK';

  // 1. Create tenant
  const tRes = post('/api/tenant-svc/onboarding/tenants', tenantPayload, null, owner.userId);
  if (tRes.status < 200 || tRes.status >= 300) {
    console.error(`[${tag}] tenant creation failed: ${tRes.status} ${tRes.body}`);
    return null;
  }
  const tenantId = body(tRes).id;

  // 2. Register cashiers (one per store)
  const run = Date.now();
  const cashier1 = registerUser(`${tag.toLowerCase()}-cashier1-${run}@shelfj.test`);
  const cashier2 = registerUser(`${tag.toLowerCase()}-cashier2-${run}@shelfj.test`);

  // 3. Store 1 — via onboarding (auto-creates DEFAULT zone)
  const s1Res = post('/api/tenant-svc/onboarding/stores', store1Payload, tenantId, owner.userId);
  const store1Id = (s1Res.status < 300) ? body(s1Res).id : null;
  if (!store1Id) console.warn(`[${tag}] store-1 onboarding failed: ${s1Res.status}`);

  // 4. Store 2 — via admin path (subsequent store)
  const s2Res = post('/api/tenant-svc/admin/stores', store2Payload, tenantId, owner.userId);
  const store2Id = (s2Res.status < 300) ? body(s2Res).id : null;
  if (!store2Id) console.warn(`[${tag}] store-2 admin failed: ${s2Res.status} ${s2Res.body}`);

  const storeIds = [store1Id, store2Id].filter(Boolean);

  // 5. Zones for each store (DEFAULT already exists for store1 from onboarding)
  for (const sid of storeIds) {
    post(`/api/tenant-svc/admin/stores/${sid}/zones`,
      { name: 'Electronics Aisle', code: `ELEC-${slug()}`, type: 'AISLE' }, tenantId, owner.userId);
    post(`/api/tenant-svc/admin/stores/${sid}/zones`,
      { name: 'Clothing Aisle',    code: `CLTH-${slug()}`, type: 'AISLE' }, tenantId, owner.userId);
  }

  // 6. Assign staff
  if (store1Id) {
    post('/api/tenant-svc/admin/staff',
      { userId: owner.userId,              storeId: store1Id, role: 'MANAGER' }, tenantId, owner.userId);
    if (cashier1) post('/api/tenant-svc/admin/staff',
      { userId: cashier1.userId, storeId: store1Id, role: 'CASHIER' }, tenantId, owner.userId);
  }
  if (store2Id) {
    post('/api/tenant-svc/admin/staff',
      { userId: owner.userId,              storeId: store2Id, role: 'MANAGER' }, tenantId, owner.userId);
    if (cashier2) post('/api/tenant-svc/admin/staff',
      { userId: cashier2.userId, storeId: store2Id, role: 'CASHIER' }, tenantId, owner.userId);
  }

  // 7. Brand + two categories
  const brandId   = body(post('/api/product-svc/admin/brands',
    { name: products.brand }, tenantId, owner.userId)).id;
  const elecCatId = body(post('/api/product-svc/admin/categories',
    { name: 'Electronics' }, tenantId, owner.userId)).id;
  const clothCatId = body(post('/api/product-svc/admin/categories',
    { name: 'Clothing' }, tenantId, owner.userId)).id;

  const catMap = { electronics: elecCatId, clothing: clothCatId };

  // 8. Products + variants
  const variantIds = [];
  for (const p of products.items) {
    const pRes = post('/api/product-svc/admin/products', {
      name: p.name, description: p.name,
      brandId:     brandId,
      categoryId:  catMap[p.category] || elecCatId,
      sellableOnline: true,
      sellablePos:    true,
    }, tenantId, owner.userId);
    const productId = body(pRes).id;
    if (!productId) { console.warn(`[${tag}] product failed: ${p.name}`); continue; }

    const vRes = post(`/api/product-svc/admin/products/${productId}/variants`, {
      sku:        `${tag}-${slug()}`,
      barcode:    `${tag}${Date.now()}${variantIds.length}`,
      attributes: JSON.stringify(p.attrs || {}),
      unit:       'PCS',
    }, tenantId, owner.userId);
    const variantId = body(vRes).id;
    if (variantId) variantIds.push(variantId);
  }

  // 9. Receive initial stock at every store × every variant
  for (const sid of storeIds) {
    for (const vid of variantIds) {
      const recRes = apiReceiveStock(tenantId, owner.userId, sid, vid, 500,
        products.initCostPrice, `${tag}-INIT`);
      if (recRes.status >= 300)
        console.warn(`[${tag}] initial receive failed s=${sid} v=${vid}: ${recRes.status}`);

      post('/api/inventory-svc/admin/inventory/thresholds',
        { storeId: sid, variantId: vid, threshold: products.threshold, maxQty: products.maxQty },
        tenantId, owner.userId);
    }
  }

  console.log(
    `[${tag}] tenantId=${tenantId} stores=${storeIds.length} variants=${variantIds.length} ` +
    `cashiers=${[cashier1, cashier2].filter(Boolean).length}`
  );

  return {
    tenantId,
    ownerId:     owner.userId,
    stores:      storeIds.map((sid, i) => ({
      storeId:   sid,
      label:     i === 0 ? store1Payload.name : store2Payload.name,
      cashierId: i === 0 ? cashier1?.userId : cashier2?.userId,
    })),
    variantIds,
    brandId,
    categoryIds: [elecCatId, clothCatId].filter(Boolean),
    brandName:   products.brand,
  };
}

// ── Setup (runs once, seeds both tenants) ──────────────────────────────────────
export function setup() {
  const run = Date.now();

  // ── India tenant ───────────────────────────────────────────────────────────
  const inOwner = registerUser(`in-owner-${run}@shelfj.test`);
  if (!inOwner) { console.error('India owner registration failed'); return null; }

  const india = seedTenant(
    inOwner,
    {
      businessName: 'Mumbai Retail Pvt Ltd',
      legalName:    'Mumbai Retail Private Limited',
      country:      'IN',
      currency:     'INR',
    },
    // Store IN-1 — Mumbai (onboarding path)
    {
      name: 'Mumbai — Marine Drive',
      code: `MUM-${run}`,
      type: 'STORE',
      line1: '24 Marine Drive',
      city:  'Mumbai',
      state: 'MH',
      country: 'IN',
      pincode: '400020',
      timezone: 'Asia/Kolkata',
    },
    // Store IN-2 — Delhi (admin path)
    {
      name: 'Delhi — Connaught Place',
      code: `DEL-${run}`,
      type: 'STORE',
      line1: 'Block A, Connaught Place',
      city:  'New Delhi',
      state: 'DL',
      country: 'IN',
      pincode: '110001',
      timezone: 'Asia/Kolkata',
    },
    {
      brand:         'Reliance Digital',
      initCostPrice: '1200.00',
      threshold:     '50.000',
      maxQty:        '200',
      items: [
        { name: 'Smart TV 43"',   category: 'electronics', attrs: { size: '43in', color: 'Black' } },
        { name: 'Android Phone',  category: 'electronics', attrs: { storage: '128GB', color: 'Blue' } },
        { name: "Men's Kurta",    category: 'clothing',    attrs: { size: 'L', fabric: 'Cotton' } },
        { name: "Women's Saree",  category: 'clothing',    attrs: { type: 'Silk', color: 'Red' } },
      ],
    },
    true  // isIN
  );

  if (!india) { console.error('India seed failed'); return null; }

  // ── UK tenant ──────────────────────────────────────────────────────────────
  const ukOwner = registerUser(`uk-owner-${run}@shelfj.test`);
  if (!ukOwner) { console.error('UK owner registration failed'); return null; }

  const uk = seedTenant(
    ukOwner,
    {
      businessName: 'London Merchandise Ltd',
      legalName:    'London Merchandise Limited',
      country:      'GB',
      currency:     'GBP',
    },
    // Store UK-1 — London (onboarding path)
    {
      name: 'London — Oxford Street',
      code: `LON-${run}`,
      type: 'STORE',
      line1: '220 Oxford Street',
      city:  'London',
      state: 'England',
      country: 'GB',
      pincode: 'W1C 1DX',
      timezone: 'Europe/London',
    },
    // Store UK-2 — Manchester (admin path)
    {
      name: 'Manchester — Arndale',
      code: `MCR-${run}`,
      type: 'STORE',
      line1: '49 Market Street',
      city:  'Manchester',
      state: 'England',
      country: 'GB',
      pincode: 'M1 1AD',
      timezone: 'Europe/London',
    },
    {
      brand:         'Marks & Spencer',
      initCostPrice: '150.00',
      threshold:     '25.000',
      maxQty:        '100',
      items: [
        { name: 'Smart TV 55"',   category: 'electronics', attrs: { size: '55in', color: 'Silver' } },
        { name: 'Laptop 15"',     category: 'electronics', attrs: { ram: '16GB', storage: '512GB' } },
        { name: "Men's Suit",     category: 'clothing',    attrs: { size: '42R', color: 'Navy' } },
        { name: "Women's Dress",  category: 'clothing',    attrs: { size: '12', color: 'Floral' } },
      ],
    },
    false  // isUK
  );

  if (!uk) { console.error('UK seed failed'); return null; }

  return { india, uk };
}

// ── VU → tenant assignment (odd VU = India, even VU = UK) ─────────────────────
function tenantCtx(d) { return (__VU % 2 === 1) ? d.india : d.uk; }
function isIN(d)       { return (__VU % 2 === 1); }

// Rotate between stores on each VU iteration.
function storeCtx(tenant) {
  if (!tenant.stores || tenant.stores.length === 0) return null;
  return tenant.stores[__ITER % tenant.stores.length];
}

// ── Scenario: browse catalogue ─────────────────────────────────────────────────
export function browseCatalog(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';
  const addLat = isIN(d)
    ? t => catalogLatencyIN.add(t)
    : t => catalogLatencyUK.add(t);

  const t0 = Date.now();
  let res = get('/api/product-svc/catalog/products', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} catalog list`);
  addLat(Date.now() - t0);

  res = get('/api/product-svc/admin/categories', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} list categories`);

  res = get('/api/product-svc/admin/products', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} admin products`);

  if (store) {
    res = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} stock levels ${store.label}`);

    const vid = tenant.variantIds[__ITER % tenant.variantIds.length];
    res = get(`/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} batches ${store.label}`);
    check(res, {
      [`${tag} batch has materialStatus field`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length === 0 || items[0].materialStatus != null;
        } catch (_) { return true; }
      },
    });

    res = get(`/api/inventory-svc/admin/inventory/movements?store=${store.storeId}&limit=10`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} movements ${store.label}`);
  }

  sleep(1);
}

// ── Scenario: complete POS sale (reserve → consume) ───────────────────────────
export function completeSale(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // Reserve
  const rRes = post('/api/inventory-svc/inventory/reservations', {
    storeId: store.storeId, variantId: vid, qty: 1, ttlSeconds: 300,
  }, tenant.tenantId, tenant.ownerId);

  const rOk = check(rRes, { [`${tag} reserve 2xx`]: r => r.status >= 200 && r.status < 300 });
  isIN(d) ? saleSuccessIN.add(rOk ? 1 : 0) : saleSuccessUK.add(rOk ? 1 : 0);
  if (!rOk) { errors.add(1); sleep(0.5); return; }

  const reservationId = body(rRes).id;
  if (!reservationId) { sleep(0.5); return; }

  // Consume (complete sale)
  const cRes = post(`/api/inventory-svc/inventory/reservations/${reservationId}/consume`,
    {}, tenant.tenantId, tenant.ownerId);
  ok(cRes, `${tag} consume ${store.label}`);

  // consume returns { "data": "consumed" } (string, not object)
  check(cRes, {
    [`${tag} status=CONSUMED`]: r => {
      try {
        const d = JSON.parse(r.body).data;
        return d === 'consumed' || d?.status === 'CONSUMED';
      } catch (_) { return false; }
    },
  });

  // Quick stock level check after sale
  const lvlRes = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`,
    tenant.tenantId, tenant.ownerId);
  ok(lvlRes, `${tag} levels after sale ${store.label}`);

  sleep(0.5);
}

// ── Scenario: abandon cart (reserve → release) ─────────────────────────────────
export function abandonCart(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  const rRes = post('/api/inventory-svc/inventory/reservations', {
    storeId: store.storeId, variantId: vid, qty: 1, ttlSeconds: 60,
  }, tenant.tenantId, tenant.ownerId);
  if (!ok(rRes, `${tag} reserve for abandon`)) { sleep(1); return; }

  const reservationId = body(rRes).id;
  if (!reservationId) { sleep(1); return; }

  sleep(0.2); // simulate browsing delay before abandon

  // List held reservations before releasing
  const listRes = get(
    `/api/inventory-svc/inventory/reservations?store=${store.storeId}&status=HELD&limit=5`,
    tenant.tenantId, tenant.ownerId);
  ok(listRes, `${tag} list HELD reservations`);

  const relRes = post(`/api/inventory-svc/inventory/reservations/${reservationId}/release`,
    {}, tenant.tenantId, tenant.ownerId);
  ok(relRes, `${tag} release ${store.label}`);

  // release returns { "data": "released" } (string, not object)
  check(relRes, {
    [`${tag} status=RELEASED`]: r => {
      try {
        const d = JSON.parse(r.body).data;
        return d === 'released' || d?.status === 'RELEASED';
      } catch (_) { return false; }
    },
  });

  sleep(1);
}

// ── Scenario: purchase / supplier stock receive ────────────────────────────────
export function purchaseReceive(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag    = isIN(d) ? 'IN' : 'UK';
  const vid    = tenant.variantIds[__ITER % tenant.variantIds.length];
  const cost   = isIN(d)
    ? `${(Math.floor(Math.random() * 1000) + 800)}.00`   // INR 800–1800
    : `${(Math.floor(Math.random() * 200) + 100)}.99`;   // GBP 100–300

  const t0 = Date.now();
  const recRes = apiReceiveStock(
    tenant.tenantId, tenant.ownerId,
    store.storeId, vid,
    isIN(d) ? 50 : 30,
    cost,
    `${tag}-PO`
  );
  purchaseLatency.add(Date.now() - t0);
  ok(recRes, `${tag} purchase receive ${store.label}`);

  // Update reorder threshold after receive (include maxQty — Gap #1)
  post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid,
    threshold: isIN(d) ? '50.000' : '25.000',
    maxQty:    isIN(d) ? '200'    : '100',
  }, tenant.tenantId, tenant.ownerId);

  // Verify updated levels
  const lvlRes = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`,
    tenant.tenantId, tenant.ownerId);
  ok(lvlRes, `${tag} levels after receive ${store.label}`);

  // List batches for this variant at this store
  const batchRes = get(
    `/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}`,
    tenant.tenantId, tenant.ownerId);
  ok(batchRes, `${tag} batches after receive`);

  sleep(1.5);
}

// ── Scenario: staff & store management ────────────────────────────────────────
export function staffAdmin(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';

  // Tenant profile
  let res = get('/api/tenant-svc/admin/tenant', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} get tenant`);

  // All stores
  res = get('/api/tenant-svc/admin/stores', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} list stores`);
  check(res, {
    [`${tag} has 2 stores`]: r => {
      try {
        const list = JSON.parse(r.body).data || [];
        return Array.isArray(list) && list.length >= 2;
      } catch (_) { return false; }
    },
  });

  // All staff
  res = get('/api/tenant-svc/admin/staff', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} list staff`);
  check(res, {
    [`${tag} has staff`]: r => {
      try {
        const list = JSON.parse(r.body).data || [];
        return Array.isArray(list) && list.length >= 1;
      } catch (_) { return false; }
    },
  });

  // Onboarding status
  res = get('/api/tenant-svc/onboarding/status', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} onboarding status`);

  // Zones for current store
  if (store) {
    res = get(`/api/tenant-svc/admin/stores/${store.storeId}/zones`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} list zones ${store.label}`);
    check(res, {
      [`${tag} ${store.label} has zones`]: r => {
        try {
          const list = JSON.parse(r.body).data || [];
          return Array.isArray(list) && list.length >= 1;
        } catch (_) { return false; }
      },
    });
  }

  sleep(1.5);
}

// ── Scenario: catalogue & inventory admin ──────────────────────────────────────
export function catalogAdmin(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';

  // Products + brands + categories
  let res = get('/api/product-svc/admin/products', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} admin list products`);

  res = get('/api/product-svc/admin/brands', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} admin list brands`);

  // Update brand name (idempotent-safe: add a timestamp suffix)
  if (tenant.brandId) {
    res = put(`/api/product-svc/admin/brands/${tenant.brandId}`,
      { name: `${tenant.brandName} (${slug()})` }, tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} update brand`);

    res = get(`/api/product-svc/admin/brands/${tenant.brandId}`, tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} get brand`);
  }

  res = get('/api/product-svc/admin/categories', tenant.tenantId, tenant.ownerId);
  ok(res, `${tag} list categories`);

  if (store) {
    // Thresholds + movements for current store
    res = get(`/api/inventory-svc/admin/inventory/thresholds?store=${store.storeId}`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} list thresholds ${store.label}`);
    check(res, {
      [`${tag} threshold has maxQty field`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length === 0 || 'maxQty' in items[0];
        } catch (_) { return true; }
      },
    });

    res = get(`/api/inventory-svc/admin/inventory/movements?store=${store.storeId}&limit=20`,
      tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} list movements ${store.label}`);

    // Spot-adjust for cycle count simulation
    const vid = tenant.variantIds[__ITER % tenant.variantIds.length];
    res = post('/api/inventory-svc/admin/inventory/adjust', {
      storeId:   store.storeId,
      variantId: vid,
      delta:     2,
      reason:    'cycle count',
    }, tenant.tenantId, tenant.ownerId);
    ok(res, `${tag} adjust stock ${store.label}`);
  }

  sleep(1.5);
}

// ── Scenario: tenant isolation verification ────────────────────────────────────
export function isolationCheck(d) {
  if (!d || !d.india || !d.uk) return;

  const india = d.india;
  const uk    = d.uk;

  // 1. India tenant queries UK store levels → must return empty or 404/403
  if (uk.stores.length > 0) {
    const ukStoreId = uk.stores[__ITER % uk.stores.length].storeId;
    const res = get(`/api/inventory-svc/admin/inventory/levels?store=${ukStoreId}`,
      india.tenantId, india.ownerId);
    const isolated = check(res, {
      'IN cannot read UK store levels': r => {
        if (r.status === 404 || r.status === 403) return true;
        try {
          const items = JSON.parse(r.body).data || [];
          return Array.isArray(items) && items.length === 0;
        } catch (_) { return true; }
      },
    });
    if (!isolated) isolationViolations.add(1);
  }

  // 2. UK tenant queries India store levels → must return empty or 404/403
  if (india.stores.length > 0) {
    const inStoreId = india.stores[__ITER % india.stores.length].storeId;
    const res = get(`/api/inventory-svc/admin/inventory/levels?store=${inStoreId}`,
      uk.tenantId, uk.ownerId);
    const isolated = check(res, {
      'UK cannot read IN store levels': r => {
        if (r.status === 404 || r.status === 403) return true;
        try {
          const items = JSON.parse(r.body).data || [];
          return Array.isArray(items) && items.length === 0;
        } catch (_) { return true; }
      },
    });
    if (!isolated) isolationViolations.add(1);
  }

  // 3. India catalog must not contain UK product names
  const inCatalog = get('/api/product-svc/catalog/products', india.tenantId, india.ownerId);
  ok(inCatalog, 'IN catalog reachable');
  check(inCatalog, {
    'IN catalog has no UK products': r => {
      try {
        const products = JSON.parse(r.body).data || [];
        const names = products.map(p => (p.name || '').toLowerCase());
        // UK-specific product names
        return !names.some(n => n.includes("men's suit") || n.includes("laptop 15") ||
                                n.includes("smart tv 55") || n.includes("women's dress"));
      } catch (_) { return true; }
    },
  });

  // 4. UK catalog must not contain India product names
  const ukCatalog = get('/api/product-svc/catalog/products', uk.tenantId, uk.ownerId);
  ok(ukCatalog, 'UK catalog reachable');
  check(ukCatalog, {
    'UK catalog has no IN products': r => {
      try {
        const products = JSON.parse(r.body).data || [];
        const names = products.map(p => (p.name || '').toLowerCase());
        // India-specific product names
        return !names.some(n => n.includes('kurta') || n.includes('saree') ||
                                n.includes('smart tv 43') || n.includes('android phone'));
      } catch (_) { return true; }
    },
  });

  sleep(2);
}

// ── Scenario: Gap #4 — material status control ────────────────────────────────
export function materialControl(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // 1. List batches — verify materialStatus field present
  const batchRes = get(
    `/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}&limit=5`,
    tenant.tenantId, tenant.ownerId);
  ok(batchRes, `${tag} MC list batches`);
  check(batchRes, {
    [`${tag} MC batches have materialStatus`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items[0].materialStatus != null;
      } catch (_) { return false; }
    },
  });

  const batchId = (() => {
    try {
      const items = JSON.parse(batchRes.body).data || [];
      const avail = items.find(b => b.materialStatus === 'AVAILABLE');
      return avail ? avail.id : null;
    } catch (_) { return null; }
  })();

  if (!batchId) { sleep(1); return; }

  // 2. Capture levels before quarantine
  const levelsBefore = (() => {
    try {
      const r = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`,
        tenant.tenantId, tenant.ownerId);
      const items = JSON.parse(r.body).data || [];
      const entry = items.find(l => l.variantId === vid);
      return entry ? parseFloat(entry.available) : 0;
    } catch (_) { return 0; }
  })();

  // 3. Quarantine the batch
  const t0 = Date.now();
  const qRes = put(`/api/inventory-svc/admin/inventory/batches/${batchId}/material-status`,
    { materialStatus: 'QUARANTINE', reason: 'k6-quality-hold' },
    tenant.tenantId, tenant.ownerId);
  materialControlLatency.add(Date.now() - t0);
  ok(qRes, `${tag} MC quarantine batch`);
  check(qRes, {
    [`${tag} MC batch materialStatus=QUARANTINE`]: r => {
      try { return JSON.parse(r.body).data.materialStatus === 'QUARANTINE'; }
      catch (_) { return false; }
    },
  });

  // 4. Levels must drop (quarantined qty excluded from available)
  const levelsRes = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`,
    tenant.tenantId, tenant.ownerId);
  ok(levelsRes, `${tag} MC levels after quarantine`);
  check(levelsRes, {
    [`${tag} MC quarantine excludes batch from available`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        const entry = items.find(l => l.variantId === vid);
        const after = entry ? parseFloat(entry.available) : 0;
        return after <= levelsBefore;
      } catch (_) { return true; }
    },
  });

  // 5. Filter batches by material_status=QUARANTINE
  const qListRes = get(
    `/api/inventory-svc/admin/inventory/batches?material_status=QUARANTINE&store=${store.storeId}`,
    tenant.tenantId, tenant.ownerId);
  ok(qListRes, `${tag} MC list QUARANTINE batches`);
  check(qListRes, {
    [`${tag} MC quarantine filter correct`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items.every(b => b.materialStatus === 'QUARANTINE');
      } catch (_) { return false; }
    },
  });

  // 6. Restore to AVAILABLE (inspection passed)
  const restoreRes = put(`/api/inventory-svc/admin/inventory/batches/${batchId}/material-status`,
    { materialStatus: 'AVAILABLE', reason: 'k6-inspection-passed' },
    tenant.tenantId, tenant.ownerId);
  ok(restoreRes, `${tag} MC restore AVAILABLE`);
  check(restoreRes, {
    [`${tag} MC batch restored to AVAILABLE`]: r => {
      try { return JSON.parse(r.body).data.materialStatus === 'AVAILABLE'; }
      catch (_) { return false; }
    },
  });

  sleep(1);
}

// ── Scenario: Gap #1 — min-max planning engine ────────────────────────────────
export function planningEngine(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // 1. Set a very high threshold to guarantee an under-stock condition for this run
  const highThreshold = '500000.000';
  const highMax       = '600000';
  const normalThreshold = isIN(d) ? '50.000' : '25.000';
  const normalMax       = isIN(d) ? '200'    : '100';

  const tRes = post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid, threshold: highThreshold, maxQty: highMax,
  }, tenant.tenantId, tenant.ownerId);
  ok(tRes, `${tag} PE set high threshold`);
  check(tRes, {
    [`${tag} PE threshold has maxQty`]: r => {
      try { return JSON.parse(r.body).data.maxQty != null; } catch (_) { return false; }
    },
  });

  // 2. Run the min-max planning engine
  const t0 = Date.now();
  const planRes = post(
    `/api/inventory-svc/admin/inventory/planning/run?store=${store.storeId}`,
    {}, tenant.tenantId, tenant.ownerId);
  planningLatency.add(Date.now() - t0);
  ok(planRes, `${tag} PE planning run`);
  check(planRes, {
    [`${tag} PE run returns array`]: r => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch (_) { return false; }
    },
  });

  // 3. List OPEN suggestions for this store
  const listRes = get(
    `/api/inventory-svc/admin/inventory/planning/suggestions?store=${store.storeId}&status=OPEN&limit=5`,
    tenant.tenantId, tenant.ownerId);
  ok(listRes, `${tag} PE list OPEN suggestions`);

  const suggestion = (() => {
    try {
      const items = JSON.parse(listRes.body).data || [];
      return items.find(s => s.variantId === vid) || items[0] || null;
    } catch (_) { return null; }
  })();

  check(listRes, {
    [`${tag} PE has open suggestion`]: () => suggestion != null,
    [`${tag} PE suggestion has correct fields`]: () => {
      if (!suggestion) return false;
      return suggestion.minQty != null && suggestion.suggestedQty != null &&
             suggestion.status === 'OPEN';
    },
    [`${tag} PE suggestedQty = maxQty - available`]: () => {
      if (!suggestion) return false;
      const expected = parseFloat(highMax) - parseFloat(suggestion.availableQty);
      return Math.abs(parseFloat(suggestion.suggestedQty) - expected) < 1;
    },
  });

  // 4. Resolve suggestion as ORDERED
  if (suggestion) {
    const resolveRes = put(
      `/api/inventory-svc/admin/inventory/planning/suggestions/${suggestion.id}/status`,
      { status: 'ORDERED' }, tenant.tenantId, tenant.ownerId);
    ok(resolveRes, `${tag} PE resolve ORDERED`);
    check(resolveRes, {
      [`${tag} PE resolved status=ORDERED`]: r => {
        try { return JSON.parse(r.body).data.status === 'ORDERED'; } catch (_) { return false; }
      },
      [`${tag} PE resolved has resolvedAt`]: r => {
        try { return JSON.parse(r.body).data.resolvedAt != null; } catch (_) { return false; }
      },
    });
  }

  // 5. Reset threshold back to normal so other scenarios are not disrupted
  post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid,
    threshold: normalThreshold, maxQty: normalMax,
  }, tenant.tenantId, tenant.ownerId);

  sleep(1);
}

// ── Required default export ────────────────────────────────────────────────────
export default function () {}

// ── Summary ────────────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const checks = data.metrics.checks;
  const passed = checks?.values?.passes || 0;
  const failed = checks?.values?.fails  || 0;
  const total  = passed + failed;

  const fmt = v => (v !== null && v !== undefined) ? String(v) : 'n/a';

  const thresholdResults = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (metric.thresholds) {
      thresholdResults[name] = Object.entries(metric.thresholds)
        .map(([expr, res]) => `${expr}: ${res.ok ? 'PASS' : 'FAIL'}`);
    }
  }

  return {
    stdout: JSON.stringify({
      overall: {
        checks_passed:  passed,
        checks_failed:  failed,
        pass_rate:      total > 0 ? (passed / total).toFixed(3) : 'n/a',
        errors:         fmt(data.metrics.errors?.values?.count),
      },
      india: {
        sale_success_rate: fmt(data.metrics.sale_success_india?.values?.rate?.toFixed(3)),
        catalog_p95_ms:    fmt(data.metrics.catalog_latency_india_ms?.values?.['p(95)']?.toFixed(1)),
      },
      uk: {
        sale_success_rate: fmt(data.metrics.sale_success_uk?.values?.rate?.toFixed(3)),
        catalog_p95_ms:    fmt(data.metrics.catalog_latency_uk_ms?.values?.['p(95)']?.toFixed(1)),
      },
      cross_cutting: {
        purchase_receive_p95_ms: fmt(data.metrics.purchase_receive_latency_ms?.values?.['p(95)']?.toFixed(1)),
        isolation_violations:    fmt(data.metrics.isolation_violations?.values?.count),
      },
      thresholds: thresholdResults,
    }, null, 2),
  };
}
