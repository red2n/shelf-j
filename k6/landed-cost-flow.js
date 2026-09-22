// Landed cost apportionment (07.x) — freight, duty, insurance landed on a goods receipt, spread over
// its lines exactly, posted to the stock and the accrual, and carried into the cost of the batches
// inventory-svc holds; reversed with a reason and taken back; every refusal named; another business
// kept out.
//
// What this proves that the ITs cannot: the receipt's batch carries the order's price (the
// GoodsReceived event now says what the goods cost), the charge reaches that batch through Kafka
// and lifts its cost by the per-unit share, and a reversal restores it to the penny — because the
// batch keeps four places, not two.
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  newId,
  onboardTenant,
  poll,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] }, setupTimeout: '3m' };
const flowCompleted = new Counter('flow_completed');

const P = '/api/purchase-svc';
const INV = '/api/inventory-svc/admin/inventory';

export function setup() {
  const tenant = onboardTenant('landed', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('landed-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const apples = sellableVariant(tenant, 'Landed apples').variantId;
  const pears = sellableVariant(tenant, 'Landed pears').variantId;
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, apples, pears, storekeeper, cashier };
}

export default function ({ tenant, rival, store, apples, pears, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const today = new Date().toISOString().slice(0, 10);
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };
  const post = (path, body, token = owner, idem = true) => call('POST', path, { token, body, idem });
  const get = (path, token = owner) => call('GET', path, { token });
  const num = (v) => Number(v || 0);
  const ledger = (sourceType) => list(get(`${P}/nominal-ledger?limit=100&from=${today}&to=${today}`)).filter((e) => e.sourceType === sourceType);
  const sum = (entries, code, side) => entries.filter((e) => e.nominalCode === code).reduce((t, e) => t + num(e[side]), 0);
  const batches = (variantId) => list(get(`${INV}/batches?store=${store.id}&variant=${variantId}`));
  const cost = (variantId) => { const b = batches(variantId); return b.length ? num(b[0].costPrice) : NaN; };
  const near = (a, b) => Math.abs(a - b) < 0.00005;
  const lineOf = (charge, variantId) => ((charge && charge.lines) || []).find((l) => l.variantId === variantId) || {};

  // ── an order received: 10 apples at 2.50 and 5 pears at 3.00 ───────────────────────────────
  const supplier = must(post(`${P}/suppliers`, { name: `Landed carrier ${uniq()}`, vatRegistered: true, currency: 'GBP' }, owner, false), 201, 'supplier');
  const po = must(post(`${P}/purchase-orders`, { supplierId: supplier.id, storeId: store.id }, owner, false), 201, 'purchase order');
  must(post(`${P}/purchase-orders/${po.id}/lines`, { variantId: apples, qty: 10, unitPrice: '2.50' }, owner, false), 201, 'apples line');
  must(post(`${P}/purchase-orders/${po.id}/lines`, { variantId: pears, qty: 5, unitPrice: '3.00' }, owner, false), 201, 'pears line');
  must(post(`${P}/purchase-orders/${po.id}/submit`, {}, owner, false), 200, 'submit');
  const gr = must(post(`${P}/goods-receipts`, { poId: po.id, storeId: store.id, lines: [{ variantId: apples, qtyReceived: 10 }, { variantId: pears, qtyReceived: 5 }] }), 201, 'goods receipt');

  const booked = poll(45, () => near(cost(apples), 2.5) && near(cost(pears), 3.0));
  truthy('[+] the receipt\'s batches carry the order\'s price', booked >= 0, { apples: cost(apples), pears: cost(pears) });

  // ── freight by value: 25.00 of apples and 15.00 of pears share 10.00 as 6.25 and 3.75 ────────
  const freight = data(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '10.00', reference: 'CN-4471', chargedBy: supplier.id }));
  truthy('[+] freight applied', freight && freight.status === 'APPLIED' && freight.currency === 'GBP', freight);
  truthy('[+] spread by value, exactly', near(num(lineOf(freight, apples).amount), 6.25) && near(num(lineOf(freight, pears).amount), 3.75), freight && freight.lines);
  truthy('[+] with what a unit rose by', near(num(lineOf(freight, apples).perUnit), 0.625) && near(num(lineOf(freight, pears).perUnit), 0.75), freight && freight.lines);
  const posted = ledger('LANDED_COST');
  truthy('[+] posted Dr Stock / Cr Landed Costs Accrued', near(sum(posted, '1001', 'debit'), 10) && near(sum(posted, '2110', 'credit'), 10), posted);

  const lifted = poll(45, () => near(cost(apples), 3.125) && near(cost(pears), 3.75));
  truthy('[+] inventory lifted the batches\' cost by the share', lifted >= 0, { apples: cost(apples), pears: cost(pears) });

  // ── duty by quantity: 15 units share 2.00 as 1.33 and 0.67, whatever they cost ───────────────
  const duty = data(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'DUTY', basis: 'BY_QUANTITY', amount: '2.00' }, storekeeper.token));
  truthy('[+] a storekeeper lands duty by quantity', duty && near(num(lineOf(duty, apples).amount), 1.33) && near(num(lineOf(duty, pears).amount), 0.67), duty && duty.lines);
  truthy('[+] the lines sum to the charge', duty && near(duty.lines.reduce((t, l) => t + num(l.amount), 0), 2), duty && duty.lines);
  const both = poll(45, () => near(cost(apples), 3.258) && near(cost(pears), 3.884));
  truthy('[+] the second charge lands on top of the first', both >= 0, { apples: cost(apples), pears: cost(pears) });

  // ── reading back ─────────────────────────────────────────────────────────────────────────────
  truthy('[+] listed by receipt', list(get(`${P}/landed-costs?grId=${gr.id}`)).length === 2);
  truthy('[+] and by order', list(get(`${P}/landed-costs?poId=${po.id}`)).length === 2);
  const one = data(get(`${P}/landed-costs/${freight.id}`));
  truthy('[+] one charge with its lines', one && one.lines && one.lines.length === 2 && one.reference === 'CN-4471', one);

  // ── the same key twice is one charge ─────────────────────────────────────────────────────────
  const key = newId();
  const first = data(call('POST', `${P}/landed-costs`, { token: owner, idem: key, body: { grId: gr.id, chargeType: 'HANDLING', basis: 'BY_QUANTITY', amount: '1.50' } }));
  const replay = data(call('POST', `${P}/landed-costs`, { token: owner, idem: key, body: { grId: gr.id, chargeType: 'HANDLING', basis: 'BY_QUANTITY', amount: '1.50' } }));
  truthy('[+] a retried charge is the same charge', first && replay && first.id === replay.id && list(get(`${P}/landed-costs?grId=${gr.id}`)).length === 3, { first, replay });

  // ── reversal: the mirror posted, the cost taken back to the penny ────────────────────────────
  expect(post(`${P}/landed-costs/${freight.id}/reversal`, { reason: '' }, owner, false), '[-] a reversal needs a reason', 400);
  const reversed = data(post(`${P}/landed-costs/${freight.id}/reversal`, { reason: 'carrier credited the consignment' }, owner, false));
  truthy('[+] reversed, with why', reversed && reversed.status === 'REVERSED' && reversed.reversedReason === 'carrier credited the consignment', reversed);
  const mirror = ledger('LANDED_COST_REVERSAL');
  truthy('[+] the mirror posted', near(sum(mirror, '2110', 'debit'), 10) && near(sum(mirror, '1001', 'credit'), 10), mirror);
  expect(post(`${P}/landed-costs/${freight.id}/reversal`, { reason: 'again' }, owner, false), '[-] ...once', 409, 'PURCHASE_LANDED_REVERSED');
  // apples: 2.50 + 0.133 duty + 0.10 handling; pears: 3.00 + 0.134 + 0.10 — the freight gone, the rest kept.
  const restored = poll(45, () => near(cost(apples), 2.733) && near(cost(pears), 3.234));
  truthy('[+] the batches lost the freight and kept the duty and handling, to the penny', restored >= 0, { apples: cost(apples), pears: cost(pears) });

  // ── refusals, and who may press what ─────────────────────────────────────────────────────────
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'POSTAGE', basis: 'BY_VALUE', amount: '1.00' }), '[-] an unknown charge type is refused', 400, 'PURCHASE_LANDED_INVALID');
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'EVENLY', amount: '1.00' }), '[-] an unknown basis is refused', 400, 'PURCHASE_LANDED_INVALID');
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '0' }), '[-] a charge of nothing is refused', 400);
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00', currency: 'EUR' }), '[-] a charge in another currency is refused', 400, 'PURCHASE_LANDED_CURRENCY_MISMATCH');
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00', chargedBy: '01900000-0000-7000-8000-0000000000ab' }), '[-] a carrier nobody has on file is refused', 404, 'PURCHASE_SUPPLIER_NOT_FOUND');
  expect(post(`${P}/landed-costs`, { grId: '01900000-0000-7000-8000-0000000000ab', chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00' }), '[-] a receipt nobody has is refused', 404, 'PURCHASE_GRN_NOT_FOUND');
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00' }, cashier.token), '[-] a cashier does not land costs', 403);
  expect(post(`${P}/landed-costs/${duty.id}/reversal`, { reason: 'no' }, cashier.token, false), '[-] nor reverses them', 403);
  expect(get(`${P}/landed-costs/${duty.id}`, rival.owner.token), '[abuse] another business does not see the charge', 404);
  expect(post(`${P}/landed-costs`, { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00' }, rival.owner.token), '[abuse] nor lands one on the receipt', 404);
  expect(post(`${P}/landed-costs/${duty.id}/reversal`, { reason: 'mine' }, rival.owner.token, false), '[abuse] nor reverses one', 404);
  expect(call('POST', `${P}/landed-costs`, { body: { grId: gr.id, chargeType: 'FREIGHT', basis: 'BY_VALUE', amount: '1.00' } }), '[abuse] nobody at all is refused at the door', 401);

  flowCompleted.add(1);
}
