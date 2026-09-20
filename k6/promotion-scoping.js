// Promotion scoping (03.8), through the gateway: a promotion scoped to a CATEGORY reaches the
// variants of every product under it — a parent reaches its children's products — because
// product-svc now announces each product's category path and pricing-svc keeps it; a product moved
// out of the category leaves the deal; a variant added later joins it; the catalogue can be
// re-announced for a projection that arrived late; and mix-and-match — any N from the scope for a
// price — bundles the dearest units and charges the rest in full. And the refusals and the abuse
// around each.
//
//   k6/run.sh promotion-scoping
import http from 'k6/http';
import { ALL_CHECKS_PASS, BASE, call, data, expect, must, onboardTenant, poll, staffUser, truthy, uniq } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const PRODUCT = '/api/product-svc/admin';
const PRICING = '/api/pricing-svc';

export function setup() {
  const tenant = onboardTenant('promo-scope', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('promo-scope-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const t = tenant.owner.token;
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cat = (name, parentId) => must(call('POST', `${PRODUCT}/categories`, { token: t, body: { name: `${name} ${uniq()}`, ...(parentId ? { parentId } : {}) } }), 201, `category ${name}`).id;
  const drinks = cat('Drinks');
  const soft = cat('Soft drinks', drinks);
  const snacks = cat('Snacks');
  const product = (name, categoryId) => must(call('POST', `${PRODUCT}/products`, { token: t, body: { name: `${name} ${uniq()}`, categoryId, sellableOnline: true, sellablePos: true } }), 201, `product ${name}`).id;
  const variant = (productId, sku) => must(call('POST', `${PRODUCT}/products/${productId}/variants`, { token: t, body: { sku: `${sku}-${uniq()}`.slice(0, 40), unit: 'PCS' } }), 201, `variant ${sku}`).id;
  const cola = product('Cola', soft);
  const crisps = product('Crisps', snacks);
  const can = variant(cola, 'COLA-CAN');
  const bottle = variant(cola, 'COLA-BOTTLE');
  const bag = variant(crisps, 'CRISPS');
  // Prices: a can 1.50, a bottle 2.00, a bag 1.00, on one active list.
  must(call('POST', `${PRICING}/vat-rates`, { token: t, body: { code: 'T1', name: 'Standard', rate: 0.2, exempt: false, effectiveFrom: '2024-01-01T00:00:00Z' } }), [201, 409], 'vat rate');
  for (const v of [can, bottle, bag]) must(call('POST', `${PRICING}/product-vat-categories`, { token: t, body: { variantId: v, vatCode: 'T1' } }), [200, 201], 'vat category');
  const list = must(call('POST', `${PRICING}/admin/price-lists`, { token: t, body: { name: `Scope list ${uniq()}`, channel: 'ALL', currency: 'GBP', effectiveFrom: '2024-01-01T00:00:00Z' } }), 201, 'price list').id;
  // The batch answers 200 with an errors list rather than failing, so count what it priced: a
  // setup that priced nothing makes every quote a 404 and every check below meaningless.
  const priced = must(call('POST', `${PRICING}/admin/price-lists/${list}/items/batch`, { token: t, body: { items: [{ variantId: can, price: 1.5, minQty: 1 }, { variantId: bottle, price: 2.0, minQty: 1 }, { variantId: bag, price: 1.0, minQty: 1 }] } }), [200, 201], 'prices');
  if (priced.upserted !== 3) throw new Error(`setup priced ${priced.upserted} of 3: ${JSON.stringify(priced.errors)}`);
  return { tenant, rival, store, storekeeper, drinks, soft, snacks, cola, crisps, can, bottle, bag, list };
}

export default function ({ tenant, rival, store, storekeeper, drinks, soft, snacks, cola, crisps, can, bottle, bag, list }) {
  const t = tenant.owner.token;
  const quote = (lines) => data(call('POST', `${PRICING}/prices/quote`, { token: t, body: { storeId: store.id, channel: 'POS', lines } }));
  const discount = (lines) => Number(quote(lines).totalDiscount || 0);
  const promo = (body) => call('POST', `${PRICING}/admin/promotions`, { token: t, body: { channel: 'ALL', startsAt: '2024-01-01T00:00:00Z', priority: 10, ...body } });
  const scope = (id, body, token = t) => call('POST', `${PRICING}/admin/promotions/${id}/items`, { token, body });
  const stop = (id) => call('POST', `${PRICING}/admin/promotions/${id}/deactivate`, { token: t, body: { reason: 'done' } });

  // ── a category scope, honoured through the parent ────────────────────────────
  const tenOff = promo({ name: `Drinks 10% ${uniq()}`, type: 'PERCENT', value: 10 });
  expect(tenOff, '[+] a 10% promotion is created', 201);
  expect(scope(data(tenOff).id, { scopeType: 'CATEGORY', scopeId: drinks }), '[+] scoped to Drinks, the parent category', 201);
  expect(scope(data(tenOff).id, { scopeType: 'CATEGORY' }), '[-] a category scope with no category', 400, 'PRICING_INVALID_SCOPE');
  expect(scope(data(tenOff).id, { scopeType: 'CATEGORY', scopeId: 'drinks' }), '[-] a category that is not an id', 400);
  expect(scope(data(tenOff).id, { scopeType: 'AISLE', scopeId: drinks }), '[-] a scope that is not one', 400, 'PRICING_INVALID_SCOPE');
  expect(scope(data(tenOff).id, { scopeType: 'CATEGORY', scopeId: drinks }, storekeeper.token), '[-] a storekeeper cannot scope a promotion', 403);
  const arrived = poll(40, () => discount([{ variantId: can, qty: 2 }]) > 0);
  truthy('[+] the catalogue reached pricing-svc within 40 s: two cans in Soft drinks get the Drinks discount', arrived >= 0, quote([{ variantId: can, qty: 2 }]));
  truthy('[+] ...0.30 off two cans at 1.50', Math.abs(discount([{ variantId: can, qty: 2 }]) - 0.3) < 0.005);
  truthy('[+] ...and the bottle too, through the same parent', Math.abs(discount([{ variantId: bottle, qty: 1 }]) - 0.2) < 0.005);
  truthy('[-] ...but not the crisps, in Snacks', discount([{ variantId: bag, qty: 3 }]) === 0, quote([{ variantId: bag, qty: 3 }]));
  truthy("[-] the rival shop's quote of the same variants gets nothing", Number(data(call('POST', `${PRICING}/prices/quote`, { token: rival.owner.token, body: { storeId: rival.stores[0].id, channel: 'POS', lines: [{ variantId: can, qty: 2 }] } })).totalDiscount || 0) === 0);

  // ── the catalogue moves, and the deal follows it ─────────────────────────────
  expect(call('PUT', `${PRODUCT}/products/${cola}`, { token: t, body: { name: `Cola ${uniq()}`, categoryId: snacks, sellableOnline: true, sellablePos: true } }), '[+] cola is moved to Snacks', 200);
  truthy('[+] ...and leaves the drinks deal within 40 s', poll(40, () => discount([{ variantId: can, qty: 2 }]) === 0) >= 0, quote([{ variantId: can, qty: 2 }]));
  expect(call('PUT', `${PRODUCT}/products/${cola}`, { token: t, body: { name: `Cola ${uniq()}`, categoryId: soft, sellableOnline: true, sellablePos: true } }), '[+] and back to Soft drinks', 200);
  truthy('[+] ...and is in the deal again', poll(40, () => discount([{ variantId: can, qty: 2 }]) > 0) >= 0);
  const multipack = must(call('POST', `${PRODUCT}/products/${cola}/variants`, { token: t, body: { sku: `COLA-6-${uniq()}`.slice(0, 40), unit: 'PCS' } }), 201, 'a new variant').id;
  must(call('POST', `${PRICING}/product-vat-categories`, { token: t, body: { variantId: multipack, vatCode: 'T1' } }), [200, 201], 'vat');
  must(call('POST', `${PRICING}/admin/price-lists/${list}/items`, { token: t, body: { variantId: multipack, price: 6.0, minQty: 1 } }), [200, 201], 'price');
  truthy('[+] a variant added to cola later joins the deal within 40 s', poll(40, () => Math.abs(discount([{ variantId: multipack, qty: 1 }]) - 0.6) < 0.005) >= 0, quote([{ variantId: multipack, qty: 1 }]));
  expect(stop(data(tenOff).id), '[+] the 10% is stopped', 200);

  // ── re-announcing the catalogue ──────────────────────────────────────────────
  const republished = call('POST', `${PRODUCT}/products/republish-catalogue`, { token: t, body: {} });
  expect(republished, '[+] the catalogue is re-announced', 200);
  truthy('[+] ...two products', data(republished).announced === 2, data(republished));
  expect(call('POST', `${PRODUCT}/products/republish-catalogue`, { token: storekeeper.token, body: {} }), '[-] not by a storekeeper', 403);
  expect(call('POST', `${PRODUCT}/products/republish-catalogue`, { token: rival.owner.token, body: {} }), '[+] the rival re-announces its own, empty, catalogue', 200);
  truthy('[+] ...none', data(call('POST', `${PRODUCT}/products/republish-catalogue`, { token: rival.owner.token, body: {} })).announced === 0);

  // ── mix and match: any 3 soft drinks for 4.00 ────────────────────────────────
  expect(promo({ name: 'x', type: 'MIX_MATCH', value: 4, buyQty: 1 }), '[-] a bundle of one is a unit price', 400, 'PRICING_INCOMPLETE_MIX_MATCH');
  expect(promo({ name: 'x', type: 'MIX_MATCH', value: 4 }), '[-] a bundle with no size', 400, 'PRICING_INCOMPLETE_MIX_MATCH');
  expect(promo({ name: 'x', type: 'MIX_MATCH', value: 4, buyQty: 2.5 }), '[-] a bundle of two and a half', 400, 'PRICING_INCOMPLETE_MIX_MATCH');
  expect(promo({ name: 'x', type: 'MIX_MATCH', value: 4, buyQty: 3, getQty: 1 }), '[-] a BOGO quantity on it', 400, 'PRICING_INVALID_PROMOTION_SHAPE');
  expect(promo({ name: 'x', type: 'PERCENT', value: 10, buyQty: 3 }), '[-] a bundle size on a percentage', 400, 'PRICING_INVALID_PROMOTION_SHAPE');
  const three = promo({ name: `Any 3 for 4 ${uniq()}`, type: 'MIX_MATCH', value: 4, buyQty: 3 });
  expect(three, '[+] any three for 4.00 is created', 201);
  truthy('[+] ...as MIX_MATCH with its bundle size', data(three).type === 'MIX_MATCH' && Number(data(three).buyQty) === 3, data(three));
  expect(scope(data(three).id, { scopeType: 'CATEGORY', scopeId: soft }), '[+] scoped to Soft drinks', 201);
  const basket = [{ variantId: bottle, qty: 2 }, { variantId: can, qty: 2 }, { variantId: bag, qty: 2 }];
  truthy('[+] two bottles, two cans, two bags: the dearest three drinks bundle (5.50 → 4.00), the rest in full', poll(20, () => Math.abs(discount(basket) - 1.5) < 0.005) >= 0, quote(basket));
  truthy('[-] two drinks: no bundle', discount([{ variantId: bottle, qty: 1 }, { variantId: can, qty: 1 }]) === 0);
  truthy('[+] six cans: two bundles, 1.00 off', Math.abs(discount([{ variantId: can, qty: 6 }]) - 1.0) < 0.005, quote([{ variantId: can, qty: 6 }]));
  truthy('[-] crisps alone: not in the deal', discount([{ variantId: bag, qty: 3 }]) === 0);
  const lines = quote(basket).lines || [];
  truthy('[+] the saving sits on the drink lines, none on the crisps', lines.some((l) => l.variantId === bag && Number(l.discount || 0) === 0) && lines.filter((l) => l.variantId !== bag).reduce((s, l) => s + Number(l.discount || 0), 0) > 1.49, lines);
  expect(stop(data(three).id), '[+] the bundle deal is stopped', 200);

  // ── abuse ────────────────────────────────────────────────────────────────────
  const hammer = http.batch(Array.from({ length: 30 }, () => ['POST', `${BASE}${PRICING}/admin/promotions`, JSON.stringify({ name: 'x', type: 'MIX_MATCH', value: 4, buyQty: 0, channel: 'ALL', startsAt: '2024-01-01T00:00:00Z' }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, tags: { name: 'POST /api/pricing-svc/admin/promotions (hammer)' } }]));
  truthy('[-] thirty malformed bundle deals: every one refused', hammer.every((r) => r.status === 400 || r.status === 429), hammer.map((r) => r.status).join(','));
  const quotes = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${PRICING}/prices/quote`, JSON.stringify({ storeId: store.id, channel: 'POS', lines: [{ variantId: can, qty: 6 }] }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, tags: { name: 'POST /api/pricing-svc/prices/quote (batch)' } }]));
  truthy('[+] twenty quotes at once all answer, none with a discount from a stopped deal', quotes.every((r) => r.status === 200 && Number((JSON.parse(r.body).data || {}).totalDiscount || 0) === 0), quotes.map((r) => r.status).join(','));
}
