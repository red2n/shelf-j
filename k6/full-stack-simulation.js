// Full-stack simulation: one business trading under concurrent load.
//
// Setup onboards a tenant with a store, an aisle, a priced catalogue of three products and stock.
// Then, at the same time:
//   browseCatalog   shoppers browse the storefront and staff read stock
//   completeSale    reserve → consume (FIFO deduction)
//   abandonCart     reserve → release
//   tillSale        POS order → cash payment → handed over
//   inventoryOps    receive, adjust, thresholds
//   adminProductOps catalogue and tenant reads, a brand update
//
//   k6/run.sh --load            (or: k6/run.sh full-stack-simulation)
import { sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  onboardTenant,
  priceVariants,
  receive,
  sellableVariant,
  truthy,
  uniq,
} from './lib/shelfj.js';

const reserveSuccess = new Rate('reserve_success_rate');
const levelLatency = new Trend('level_latency_ms', true);

export const options = {
  setupTimeout: '3m',
  scenarios: {
    browseCatalog: { executor: 'constant-vus', vus: 3, duration: '30s', exec: 'browseCatalog' },
    completeSale: { executor: 'constant-vus', vus: 2, duration: '25s', exec: 'completeSale', startTime: '3s' },
    abandonCart: { executor: 'constant-vus', vus: 1, duration: '20s', exec: 'abandonCart', startTime: '5s' },
    tillSale: { executor: 'constant-vus', vus: 2, duration: '25s', exec: 'tillSale', startTime: '3s' },
    inventoryOps: { executor: 'constant-vus', vus: 1, duration: '20s', exec: 'inventoryOps', startTime: '3s' },
    adminProductOps: { executor: 'constant-vus', vus: 1, duration: '20s', exec: 'adminProductOps' },
  },
  thresholds: {
    ...ALL_CHECKS_PASS,
    reserve_success_rate: ['rate==1.0'],
    level_latency_ms: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const tenant = onboardTenant('sim', { country: 'IN', currency: 'INR', stores: 1 });
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  call('POST', `/api/tenant-svc/admin/stores/${storeId}/zones`, { token: t, body: { name: 'Aisle A', code: 'AISLE-A', type: 'AISLE' } });
  const brandId = data(call('POST', '/api/product-svc/admin/brands', { token: t, body: { name: `SimBrand ${uniq()}` } })).id;
  const variantIds = [];
  for (let p = 1; p <= 3; p++) {
    const variantId = sellableVariant(tenant, `Sim product ${p}`).variantId;
    variantIds.push(variantId);
    if (receive(tenant, storeId, variantId, 500, '250.00').status !== 201) throw new Error('receive failed');
    call('POST', '/api/inventory-svc/admin/inventory/thresholds', { token: t, body: { storeId, variantId, threshold: '20.000' } });
  }
  priceVariants(tenant, variantIds, '399.00');
  return { tenant, storeId, brandId, variantIds };
}

const pick = (list) => list[Math.floor(Math.random() * list.length)];

export function browseCatalog({ tenant, storeId, variantIds }) {
  const t = tenant.owner.token;
  expect(call('GET', '/api/product-svc/catalog/products?limit=20', { storefront: tenant.tenantId }), 'storefront catalogue', 200);
  expect(call('GET', `/api/inventory-svc/inventory/availability?store=${storeId}&variant=${pick(variantIds)}`, { storefront: tenant.tenantId }), 'storefront availability', 200);
  const started = Date.now();
  expect(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${storeId}`, { token: t }), 'stock levels', 200);
  levelLatency.add(Date.now() - started);
  expect(call('GET', `/api/inventory-svc/admin/inventory/batches?store=${storeId}&variant=${pick(variantIds)}`, { token: t }), 'batches', 200);
  expect(call('GET', `/api/inventory-svc/admin/inventory/movements?store=${storeId}&limit=10`, { token: t }), 'movements', 200);
  sleep(1);
}

function reserve(tenant, storeId, variantId, ttlSeconds) {
  const res = call('POST', '/api/inventory-svc/inventory/reservations', {
    token: tenant.owner.token,
    idem: true,
    body: { storeId, variantId, qty: 1, ttlSeconds },
  });
  const ok = expect(res, 'reserve stock', 201);
  reserveSuccess.add(ok ? 1 : 0);
  return ok ? data(res).id : null;
}

export function completeSale({ tenant, storeId, variantIds }) {
  const t = tenant.owner.token;
  const id = reserve(tenant, storeId, pick(variantIds), 300);
  if (!id) return;
  expect(call('POST', `/api/inventory-svc/inventory/reservations/${id}/consume`, { token: t, body: {} }), 'consume reservation', 200);
  truthy('reservation CONSUMED', data(call('GET', `/api/inventory-svc/inventory/reservations/${id}`, { token: t })).status === 'CONSUMED');
  sleep(0.5);
}

export function abandonCart({ tenant, storeId, variantIds }) {
  const t = tenant.owner.token;
  const id = reserve(tenant, storeId, variantIds[0], 60);
  if (!id) return;
  sleep(0.3);
  expect(call('GET', `/api/inventory-svc/inventory/reservations?store=${storeId}&status=HELD&limit=5`, { token: t }), 'list held reservations', 200);
  expect(call('POST', `/api/inventory-svc/inventory/reservations/${id}/release`, { token: t, body: {} }), 'release reservation', 200);
  sleep(1);
}

export function tillSale({ tenant, storeId, variantIds }) {
  const t = tenant.owner.token;
  const placed = call('POST', '/api/order-svc/orders', {
    token: t,
    idem: true,
    body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: pick(variantIds), qty: 1 }] },
  });
  if (!expect(placed, 'POS order placed', 201)) return;
  const order = data(placed);
  expect(
    call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId, currency: 'INR' } }),
    'cash taken',
    [200, 201]
  );
  sleep(0.5);
}

export function inventoryOps({ tenant, storeId, variantIds }) {
  const t = tenant.owner.token;
  const variantId = pick(variantIds);
  expect(receive(tenant, storeId, variantId, 10, '260.00'), 'receive stock', 201);
  expect(call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, body: { storeId, variantId, delta: 5, reason: 'cycle count' } }), 'adjust stock +5', 200);
  expect(call('GET', `/api/inventory-svc/admin/inventory/thresholds?store=${storeId}`, { token: t }), 'list thresholds', 200);
  expect(call('POST', '/api/inventory-svc/admin/inventory/thresholds', { token: t, body: { storeId, variantId, threshold: '25.000' } }), 'set threshold', [200, 201]);
  sleep(1);
}

export function adminProductOps({ tenant, storeId, brandId }) {
  const t = tenant.owner.token;
  expect(call('GET', '/api/product-svc/admin/brands', { token: t }), 'list brands', 200);
  expect(call('PUT', `/api/product-svc/admin/brands/${brandId}`, { token: t, body: { name: `SimBrand ${uniq()}` } }), 'update brand', 200);
  expect(call('GET', '/api/product-svc/admin/categories', { token: t }), 'list categories', 200);
  expect(call('GET', '/api/product-svc/admin/products', { token: t }), 'list products', 200);
  expect(call('GET', `/api/inventory-svc/admin/inventory/batches?store=${storeId}`, { token: t }), 'list batches', 200);
  expect(call('GET', '/api/tenant-svc/admin/tenant', { token: t }), 'tenant profile', 200);
  expect(call('GET', '/api/tenant-svc/admin/staff', { token: t }), 'list staff', 200);
  sleep(1.5);
}
