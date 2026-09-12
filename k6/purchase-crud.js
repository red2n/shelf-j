// purchase-svc: suppliers can be corrected after creation (SJ-D34), and the rules around it.
//
// A supplier created in the wrong currency used to be permanently wrong for every purchase order
// ever raised against it, because orders inherit the supplier's currency and nothing could change
// it. Now PUT /suppliers/{id} corrects name, VAT details, country, terms and currency — the
// currency only while no order against the supplier is open, and the orders already raised keep
// the currency they were raised in.
//
//   k6/run.sh purchase-crud
import { ALL_CHECKS_PASS, addStore, call, data, expect, must, onboardTenant, staffUser, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

export function setup() {
  const tenant = onboardTenant('purchase', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('purchase-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, cashier };
}

export default function ({ tenant, rival, store, cashier }) {
  const owner = tenant.owner.token;
  const suppliers = '/api/purchase-svc/suppliers';
  const orders = '/api/purchase-svc/purchase-orders';
  const stamp = Date.now();

  // ── the wrong currency, found at the first order ─────────────────────────
  const created = call('POST', suppliers, { token: owner, body: { name: `Yen By Mistake ${stamp}`, vatRegistered: true, currency: 'JPY' } });
  expect(created, 'a supplier is created — in the wrong currency', 201);
  const id = data(created).id;
  const po = call('POST', orders, { token: owner, body: { supplierId: id, storeId: store.id } });
  expect(po, 'a purchase order is raised against it', 201);
  truthy('and inherits the wrong currency', data(po).currency === 'JPY', data(po));

  const put = (body, opts = {}) => call('PUT', `${suppliers}/${id}`, { token: owner, body: { name: `Yen By Mistake ${stamp}`, vatRegistered: true, ...body }, ...opts });
  expect(put({ currency: 'GBP' }), 'the currency cannot change while that order is open', 409, 'PURCHASE_SUPPLIER_CURRENCY_IN_USE');
  const terms = put({ paymentTermsDays: 45, vatNumber: 'GB999999973' });
  expect(terms, 'terms and the VAT number can change under an open order', 200);
  truthy('and are stored', data(terms).paymentTermsDays === 45 && data(terms).vatNumber === 'GB999999973' && data(terms).currency === 'JPY', data(terms));
  expect(call('POST', `${orders}/${data(po).id}/cancel`, { token: owner, body: { reason: 'wrong currency' } }), 'the order is cancelled', 200);
  const fixed = put({ currency: 'gbp', countryCode: 'gb', paymentTermsDays: 45, vatNumber: 'GB999999973' });
  expect(fixed, 'now the currency is corrected', 200);
  truthy('upper-cased, the rest kept', data(fixed).currency === 'GBP' && data(fixed).countryCode === 'GB' && data(fixed).paymentTermsDays === 45, data(fixed));
  truthy('the cancelled order keeps the currency it was raised in', data(call('GET', `${orders}/${data(po).id}`, { token: owner })).currency === 'JPY');
  const next = call('POST', orders, { token: owner, body: { supplierId: id, storeId: store.id } });
  expect(next, 'the next order is raised', 201);
  truthy('and inherits the corrected currency', data(next).currency === 'GBP', data(next));
  truthy('a fresh read agrees', data(call('GET', `${suppliers}/${id}`, { token: owner })).currency === 'GBP');

  // ── refused ───────────────────────────────────────────────────────────────
  const other = call('POST', suppliers, { token: owner, body: { name: `Other Supplier ${stamp}`, vatRegistered: false } });
  expect(other, 'a second supplier', 201);
  expect(put({ name: `Other Supplier ${stamp}` }), 'renaming onto another supplier is refused', 409, 'PURCHASE_SUPPLIER_DUPLICATE');
  expect(put({ currency: 'ZZZ' }), 'a currency that is not ISO 4217 is refused', 400);
  expect(put({ paymentTermsDays: 0 }), 'zero-day terms are refused', 400);
  expect(call('PUT', `${suppliers}/${id}`, { token: owner, body: { name: '   ' } }), 'a blank name is refused', 400);
  expect(put({ paymentTermsDays: 7 }, { token: cashier.token }), 'a cashier cannot correct a supplier', 403);
  expect(put({ paymentTermsDays: 7 }, { token: rival.owner.token }), "a rival tenant's owner finds no such supplier", 404);
  truthy('none of the refusals changed anything', data(call('GET', `${suppliers}/${id}`, { token: owner })).paymentTermsDays === 45);
}
