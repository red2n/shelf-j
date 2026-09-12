// purchase-svc: suppliers can be corrected after creation (SJ-D34), and the rules around it.
//
// A supplier created in the wrong currency used to be permanently wrong for every purchase order
// ever raised against it, because orders inherit the supplier's currency and nothing could change
// it. Now PUT /suppliers/{id} corrects name, VAT details, country, terms and currency — the
// currency only while no order against the supplier is open, and the orders already raised keep
// the currency they were raised in.
//
//   k6/run.sh purchase-crud
import { ALL_CHECKS_PASS, addStore, call, data, expect, must, onboardTenant, poll, sellableVariant, staffUser, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

export function setup() {
  const tenant = onboardTenant('purchase', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('purchase-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const { variantId } = sellableVariant(tenant, 'Returnable Case');
  return { tenant, rival, store, cashier, storekeeper, variantId };
}

/** On-hand of a variant at a store, as inventory-svc's available batches sum to. */
function onHand(token, storeId, variantId) {
  const res = call('GET', `/api/inventory-svc/admin/inventory/batches?store=${storeId}&variant=${variantId}&material_status=AVAILABLE&limit=100`, { token });
  return (data(res) || []).reduce((sum, b) => sum + Number(b.remainingQty || 0), 0);
}

export default function ({ tenant, rival, store, cashier, storekeeper, variantId }) {
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

  // ── return to vendor and the debit note (07.8) ───────────────────────────
  const returns = '/api/purchase-svc/vendor-returns';
  const rtvSupplier = must(call('POST', suppliers, { token: owner, body: { name: `Crushed Cases Ltd ${stamp}`, vatRegistered: true, currency: 'GBP' } }), 201, 'rtv supplier');
  const rtvPo = must(call('POST', orders, { token: owner, body: { supplierId: rtvSupplier.id, storeId: store.id } }), 201, 'rtv order');
  expect(call('POST', `${orders}/${rtvPo.id}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: '2.50' } }), 'ten cases at 2.50 are ordered', 201);
  expect(call('POST', `${orders}/${rtvPo.id}/submit`, { token: owner, body: {} }), 'and the order submitted', 200);
  const rtv = (body, opts) => call('POST', returns, Object.assign({ token: owner, idem: true, body }, opts || {}));
  expect(rtv({ poId: rtvPo.id, reason: 'DAMAGED', lines: [{ variantId, qty: 1 }] }), 'nothing can go back before anything arrived', 409, 'PURCHASE_RTV_NOTHING_RECEIVED');

  const before = onHand(owner, store.id, variantId);
  expect(call('POST', '/api/purchase-svc/goods-receipts', { token: owner, idem: true, body: { poId: rtvPo.id, storeId: store.id, lines: [{ variantId, qtyReceived: 10 }] } }), 'ten cases are received', 201);
  const arrived = poll(30, () => onHand(owner, store.id, variantId) >= before + 10);
  truthy('and inventory-svc books them within 30 s', arrived >= 0, onHand(owner, store.id, variantId));

  expect(rtv({ poId: rtvPo.id, reason: 'FELT_LIKE_IT', lines: [{ variantId, qty: 1 }] }), 'an invented reason is refused', 400, 'PURCHASE_RTV_REASON_UNKNOWN');
  expect(rtv({ poId: rtvPo.id, reason: 'DAMAGED', lines: [] }), 'a return with nothing on it is refused', 400);
  expect(rtv({ poId: rtvPo.id, reason: 'DAMAGED', lines: [{ variantId, qty: 11 }] }), 'more than was received is refused', 422, 'PURCHASE_RTV_OVER_RETURN');
  expect(rtv({ poId: rtvPo.id, reason: 'DAMAGED', lines: [{ variantId, qty: 1 }] }, { token: cashier.token }), 'a cashier cannot send goods back', 403);
  expect(rtv({ poId: rtvPo.id, reason: 'DAMAGED', lines: [{ variantId, qty: 1 }] }, { token: rival.owner.token }), "a rival tenant's owner finds no such order", 404);

  const raised = rtv({ poId: rtvPo.id, reason: 'DAMAGED', notes: 'three cases crushed in transit', lines: [{ variantId, qty: 3 }] }, { token: storekeeper.token });
  expect(raised, 'the storekeeper sends three cases back', 201);
  const dn = data(raised);
  truthy('with a debit note at the order\'s price: 3 × 2.50 net, VAT at the order\'s code', dn.status === 'RAISED' && /^DN-\d{6}$/.test(dn.debitNoteNumber) && Number(dn.netAmount) === 7.5 && Number(dn.grossAmount) >= 7.5 && dn.lines.length === 1 && Number(dn.lines[0].unitPrice) === 2.5, dn);
  const left = poll(30, () => onHand(owner, store.id, variantId) <= before + 7);
  truthy('and the three cases leave the shelf within 30 s', left >= 0, onHand(owner, store.id, variantId));
  const progress = call('GET', `${orders}/${rtvPo.id}/progress`, { token: owner });
  truthy('the order still reads received 10, and now returned 3', (data(progress) || []).some((p) => p.variantId === variantId && Number(p.qtyReceived) === 10 && Number(p.qtyReturned) === 3), data(progress));
  truthy('and its status is untouched', data(call('GET', `${orders}/${rtvPo.id}`, { token: owner })).status === 'RECEIVED');
  expect(rtv({ poId: rtvPo.id, reason: 'QUALITY', lines: [{ variantId, qty: 8 }] }), 'seven are left to return, so eight is refused', 422, 'PURCHASE_RTV_OVER_RETURN');

  const listed = call('GET', `${returns}?poId=${rtvPo.id}`, { token: cashier.token });
  expect(listed, 'the returns on an order are read by any staff member', 200);
  truthy('one, with its lines', (data(listed) || []).length === 1 && data(listed)[0].debitNoteNumber === dn.debitNoteNumber, data(listed));
  expect(call('GET', `${returns}/${dn.id}`, { token: rival.owner.token }), "a rival tenant's owner does not read it", 404);

  const credit = { creditNoteNumber: `CN-${stamp}`, creditNoteDate: '2026-09-20' };
  expect(call('POST', `${returns}/${dn.id}/credit`, { token: storekeeper.token, body: credit }), 'a storekeeper does not record the credit note', 403);
  expect(call('POST', `${returns}/${dn.id}/credit`, { token: owner, body: { creditNoteNumber: 'CN-x', creditNoteDate: 'soon' } }), 'a date that is not a date is refused', 400);
  const credited = call('POST', `${returns}/${dn.id}/credit`, { token: owner, body: credit });
  expect(credited, 'the owner records the supplier\'s credit note', 200);
  truthy('closing the return at the debit note\'s gross', data(credited).status === 'CREDITED' && data(credited).creditNoteNumber === credit.creditNoteNumber && Number(data(credited).creditAmount) === Number(dn.grossAmount), data(credited));
  expect(call('POST', `${returns}/${dn.id}/credit`, { token: owner, body: { creditNoteNumber: 'CN-again', creditNoteDate: '2026-09-21' } }), 'a second credit note is refused, the first named', 409, 'PURCHASE_RTV_ALREADY_CREDITED');
  expect(call('DELETE', `${returns}/${dn.id}`, { token: owner }), 'nothing deletes a return', [404, 405]);
}
