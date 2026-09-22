// Ids are RFC 9562 UUIDv7, in and out (every service), through the gateway: every id the platform
// hands back is a canonical v7; every door an id comes in by — a path, a query parameter typed UUID,
// one parsed by hand, a body field typed UUID, one parsed by hand, the event id of a message — refuses
// any other version, the wrong variant and any other spelling with 400 INVALID_UUID, before anything
// is looked up; a v7 that names nothing is still a 404, not a 400. And the Idempotency-Key, in the
// header or the legacy body field: a clock reading or a v4 is 400 IDEMPOTENCY_KEY_INVALID; a v7 places
// the order once however often it is sent, in either case, including ten times at once — the SJ-D70
// case, two tills in one millisecond, made impossible.
//
//   k6/run.sh uuid-v7-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  newId,
  platformAdmin,
  register,
  sellingTenant,
  truthy,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

/** Every other kind of UUID, and every other spelling of a v7. */
const NOT_V7 = {
  v1: 'f81d4fae-7dec-11d0-a765-00a0c91e6bf6',
  v4: '919108f7-52d1-4320-9bac-f847db4148a8',
  v6: '1ec9414c-232a-6b00-b3c8-9f6bdeced846',
  v8: '320c3d4d-cc00-875b-8ec9-32d5f69181c0',
  nil: '00000000-0000-0000-0000-000000000000',
  max: 'ffffffff-ffff-ffff-ffff-ffffffffffff',
  'v7 with the Microsoft variant': '01a0905d-7082-7518-cec6-aee90d72a43e',
  'v7 without hyphens': '01a0905d708275189ec6aee90d72a43e',
  'short groups': '1-1-1-1-1',
};

const UUID_TEXT = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Every uuid in a response body, and those that are not a canonical lowercase v7. */
function uuidsIn(res) {
  const all = String(res.body).match(UUID_TEXT) || [];
  return { all, other: all.filter((u) => !V7.test(u)) };
}

export function setup() {
  const s = sellingTenant('ids');
  return { ...s, admin: platformAdmin(), shopper: register('ids-shopper') };
}

export default function ({ tenant, store, variantId, cashier, admin, shopper }) {
  const owner = tenant.owner.token;
  const order = (key, body = {}) => call('POST', '/api/order-svc/orders', {
    token: owner,
    idem: key,
    body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], ...body },
  });

  // ── what comes back is v7 ─────────────────────────────────────────────────────────────────────────
  const placed = order(newId());
  expect(placed, '[+] an order is placed with a UUIDv7 key', 201);
  const reads = [
    placed,
    call('GET', `/api/tenant-svc/admin/stores/${store.id}`, { token: owner }),
    call('GET', `/api/order-svc/orders/${data(placed).id}`, { token: owner }),
    call('GET', '/api/tenant-svc/admin/tenant', { token: owner }),
    call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store.id}&limit=5`, { token: owner }),
  ];
  const seen = reads.map(uuidsIn);
  truthy(
    '[+] every id the platform hands back — tenant, store, order, lines, stock — is a canonical v7',
    seen.every((s) => s.all.length > 0 && s.other.length === 0),
    seen.map((s) => s.other)
  );

  // ── every door refuses another kind ────────────────────────────────────────────────────────────────
  for (const [kind, id] of Object.entries(NOT_V7)) {
    expect(call('GET', `/api/tenant-svc/admin/stores/${id}`, { token: owner }), `[-] a ${kind} in a path`, 400, 'INVALID_UUID');
    expect(call('GET', `/api/pricing-svc/tax-transactions?orderId=${id}`, { token: cashier.token }), `[-] a ${kind} in a query parameter typed UUID`, 400, 'INVALID_UUID');
    expect(call('GET', `/api/inventory-svc/admin/inventory/costing-methods?store=${id}`, { token: owner }), `[-] a ${kind} in a query parameter parsed by hand`, 400, 'INVALID_UUID');
    expect(call('PUT', `/api/tenant-svc/platform/tenants/${tenant.tenantId}/plan`, { token: admin.token, body: { planId: id, reason: 'probe' } }), `[-] a ${kind} in a body field typed UUID`, 400, 'INVALID_UUID');
    expect(order(newId(), { storeId: id }), `[-] a ${kind} in a body field parsed by hand`, 400, 'INVALID_UUID');
    expect(call('POST', '/api/notification-svc/notifications/send', { token: owner, body: { channel: 'EMAIL', recipient: 'probe@example.com', subject: 's', body: 'b', eventId: id } }), `[-] a ${kind} as a message's event id`, 400, 'INVALID_UUID');
  }
  expect(call('GET', `/api/tenant-svc/admin/stores/${newId()}`, { token: owner }), '[+] a v7 that names nothing is not found, not malformed', 404);
  expect(call('GET', `/api/tenant-svc/admin/stores/${store.id.toUpperCase()}`, { token: owner }), '[+] a v7 in upper case is the same id', 200);
  expect(call('GET', `/api/order-svc/orders/${NOT_V7.v4}`, { token: shopper.token, storefront: tenant.tenantId }), '[-] a shopper\'s own-order route does not open for a v4', [400, 401, 403]);

  // ── the Idempotency-Key ────────────────────────────────────────────────────────────────────────────
  expect(order(`pos-${Date.now()}-order`), '[-] a key made from a clock reading (SJ-D70)', 400, 'IDEMPOTENCY_KEY_INVALID');
  expect(order(NOT_V7.v4), '[-] a v4 key', 400, 'IDEMPOTENCY_KEY_INVALID');
  expect(order('sf-1757590000000'), '[-] a storefront key made from a clock', 400, 'IDEMPOTENCY_KEY_INVALID');
  const key = newId();
  const first = order(key);
  expect(first, '[+] a v7 key places the order', 201);
  const again = order(key);
  truthy('[+] the same key again is the same order', [200, 201].includes(again.status) && data(again).id === data(first).id, { first: data(first).id, again: data(again).id });
  const shouted = order(key.toUpperCase());
  truthy('[+] the same key in upper case is the same key, not a second order', [200, 201].includes(shouted.status) && data(shouted).id === data(first).id, { first: data(first).id, shouted: data(shouted).id });
  const inBody = newId();
  const viaBody = call('POST', '/api/order-svc/orders', { token: owner, body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], idempotencyKey: inBody } });
  expect(viaBody, '[+] a v7 key in the legacy body field places the order', 201);
  expect(call('POST', '/api/order-svc/orders', { token: owner, body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], idempotencyKey: `sf-${Date.now()}` } }), '[-] a clock key in the legacy body field', 400, 'IDEMPOTENCY_KEY_INVALID');
  const raced = newId();
  const burst = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}/api/order-svc/orders`, JSON.stringify({ storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}`, 'Idempotency-Key': raced }, tags: { name: 'POST /api/order-svc/orders (one key, ten at once)' } }]));
  const ids = new Set(burst.filter((r) => r.status === 200 || r.status === 201).map((r) => JSON.parse(r.body).data.id));
  truthy('[abuse] one key ten times at once: one order, the rest told it exists or is in flight', ids.size === 1 && burst.every((r) => [200, 201, 409].includes(r.status)), burst.map((r) => r.status).join(','));
  const two = http.batch([newId(), newId()].map((k) => ['POST', `${BASE}/api/order-svc/orders`, JSON.stringify({ storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}`, 'Idempotency-Key': k }, tags: { name: 'POST /api/order-svc/orders (two tills, same instant)' } }]));
  truthy('[+] two tills in the same instant, each with its own v7 key: two orders', two.every((r) => r.status === 201) && JSON.parse(two[0].body).data.id !== JSON.parse(two[1].body).data.id, two.map((r) => r.status));

  completed.add(1);
}
