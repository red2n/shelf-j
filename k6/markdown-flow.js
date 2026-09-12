// Date-code markdown — reduce to clear (05.4) and the ladder that plans it (03.9),
// driven through the gateway as a counter and a till would: the ladder set by the
// owner and refused to everyone else, the morning's plan naming a short-dated batch
// with the ladder's price, the storekeeper stickering it and getting the code,
// the cashier scanning the code and selling two packs at the sticker with a third
// at the list on the same ticket, the sticker counting down, one too many refused,
// the stickers taken off and the code dead.
//
//   k6/run.sh markdown-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  onboardTenant,
  poll,
  priceVariants,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '5m' };

const PRICING = '/api/pricing-svc';

function isoDate(daysFromNow) {
  return new Date(Date.now() + daysFromNow * 86400000).toISOString().slice(0, 10);
}

export function setup() {
  const tenant = onboardTenant('markdown', { stores: 2 });
  const rival = onboardTenant('markdown-rival', { stores: 1 });
  const store = tenant.stores[0];
  const other = tenant.stores[1];
  const { variantId } = sellableVariant(tenant, 'Greek yoghurt 500g');
  const otherVariant = sellableVariant(tenant, 'Honey roast ham').variantId;
  priceVariants(tenant, [variantId, otherVariant], '4.00');
  // Ten packs that expire in two days, and ten of the ham with a week to go.
  const received = call('POST', '/api/inventory-svc/admin/inventory/receive', {
    token: tenant.owner.token,
    idem: true,
    body: { storeId: store.id, variantId, qty: 10, batchNo: `SD-${Date.now()}`.slice(0, 32), costPrice: '1.00', expiryDate: isoDate(2) },
  });
  if (received.status !== 201) throw new Error(`receive failed: ${received.status} ${received.body}`);
  const hamReceived = call('POST', '/api/inventory-svc/admin/inventory/receive', {
    token: tenant.owner.token,
    idem: true,
    body: { storeId: store.id, variantId: otherVariant, qty: 10, batchNo: `HAM-${Date.now()}`.slice(0, 32), costPrice: '1.00', expiryDate: isoDate(6) },
  });
  if (hamReceived.status !== 201) throw new Error(`receive failed: ${hamReceived.status} ${hamReceived.body}`);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const otherKeeper = staffUser(tenant, 'STOREKEEPER', [other.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  const rivalCashier = staffUser(rival, 'CASHIER', [rival.stores[0].id]);
  return { tenant, rival, store, other, variantId, otherVariant, storekeeper, otherKeeper, cashier, rivalCashier };
}

export default function ({ tenant, rival, store, other, variantId, otherVariant, storekeeper, otherKeeper, cashier, rivalCashier }) {
  const owner = tenant.owner.token;
  const ladder = (token, body) => call('PUT', `${PRICING}/markdowns/ladder`, { token, body });

  // ── the ladder (03.9) ────────────────────────────────────────────────────────
  const dflt = call('GET', `${PRICING}/markdowns/ladder?storeId=${store.id}`, { token: storekeeper.token });
  expect(dflt, 'the storekeeper reads the ladder', 200);
  truthy('which is the default until someone sets one: 3 days 25 %, 1 day 50 %, the day 75 %', data(dflt).source === 'DEFAULT' && data(dflt).steps.length === 3, data(dflt));
  const steps = [{ daysToExpiry: 5, percentOff: 20 }, { daysToExpiry: 2, percentOff: 40 }, { daysToExpiry: 0, percentOff: 70 }];
  expect(ladder(storekeeper.token, { steps }), 'a storekeeper cannot set it', 403);
  expect(ladder(cashier.token, { steps }), 'nor a cashier', 403);
  expect(ladder(owner, { steps: [{ daysToExpiry: 2, percentOff: 40 }, { daysToExpiry: 2, percentOff: 50 }] }), 'two steps at the same day are refused', 400, 'PRICING_LADDER_DUPLICATE_STEP');
  expect(ladder(owner, { steps: [{ daysToExpiry: 2, percentOff: 120 }] }), 'more than everything off is refused', 400);
  const set = ladder(owner, { steps });
  expect(set, "the owner sets the business's ladder", 200);
  truthy('5 days 20 %, 2 days 40 %, the day 70 %', data(set).source === 'TENANT' && data(set).steps.length === 3, data(set));
  truthy('a rival still reads the default', data(call('GET', `${PRICING}/markdowns/ladder`, { token: rival.owner.token })).source === 'DEFAULT');

  // ── the plan ─────────────────────────────────────────────────────────────────
  expect(call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}`, { token: cashier.token }), 'the till does not plan the counter', 403);
  expect(call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}`, { token: otherKeeper.token }), "another store's keeper does not plan this one", 403);
  expect(call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}&withinDays=0`, { token: storekeeper.token }), 'a horizon of nothing is refused', 400, 'PRICING_INVALID_HORIZON');
  const planRes = call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}&withinDays=7`, { token: storekeeper.token });
  expect(planRes, "the storekeeper reads the morning's plan", 200);
  const plan = data(planRes);
  truthy('inventory-svc was read', plan.inventoryReachable === true && plan.ladderSource === 'TENANT', plan);
  const yog = (plan.suggestions || []).find((s) => s.variantId === variantId);
  truthy('the short-dated yoghurt is on it, two days out, at the 2-day step: 4.00 → 2.40', yog && yog.daysToExpiry <= 2 && yog.stepDays === 2 && Number(yog.currentPrice) === 4 && Number(yog.suggestedPrice) === 2.4 && Number(yog.remainingQty) === 10 && !yog.existing, yog);
  const ham = (plan.suggestions || []).find((s) => s.variantId === otherVariant);
  truthy('the ham is on it with six days to go and no step yet', ham && ham.daysToExpiry === 6 && ham.stepDays == null && ham.suggestedPrice == null, ham);
  const rivalPlan = call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}`, { token: rival.owner.token });
  truthy("a rival's owner planning this store sees nothing of it", rivalPlan.status === 200 && (data(rivalPlan).suggestions || []).length === 0, rivalPlan.body);

  // ── the sticker (05.4) ───────────────────────────────────────────────────────
  const sticker = (token, body) => call('POST', `${PRICING}/markdowns`, { token, body });
  const draft = { storeId: store.id, variantId, batchId: yog.batchId, batchNo: yog.batchNo, expiryDate: yog.expiryDate, qty: 3, percentOff: 40, reason: 'SHORT_DATED' };
  expect(sticker(cashier.token, draft), 'the till does not sticker', 403);
  expect(sticker(otherKeeper.token, draft), "another store's keeper does not sticker here", 403);
  expect(sticker(storekeeper.token, Object.assign({}, draft, { reason: 'BORED' })), 'an invented reason is refused', 400, 'PRICING_MARKDOWN_REASON_UNKNOWN');
  expect(sticker(storekeeper.token, Object.assign({}, draft, { markdownPrice: 2.4 })), 'a percentage and a price together are refused', 400, 'PRICING_MARKDOWN_AMOUNT_AMBIGUOUS');
  expect(sticker(storekeeper.token, Object.assign({}, draft, { percentOff: undefined, markdownPrice: 4 })), 'a price that is no reduction is refused', 400, 'PRICING_MARKDOWN_NOT_A_REDUCTION');
  expect(sticker(storekeeper.token, Object.assign({}, draft, { expiryDate: isoDate(-1) })), 'a batch already past its date cannot be stickered', 400, 'PRICING_MARKDOWN_EXPIRED_DATE');
  expect(sticker(storekeeper.token, Object.assign({}, draft, { variantId: rival.stores[0].id })), 'a product with no price cannot be reduced', 404, 'PRICING_MARKDOWN_NO_PRICE');
  const issued = sticker(storekeeper.token, draft);
  expect(issued, 'the storekeeper stickers three packs at the step', 201);
  const md = data(issued);
  truthy('with the code the till will scan: prefix 21, the pence in it, 2.40 from 4.00', /^21\d{11}$/.test(md.labelCode) && md.labelCode.slice(7, 12) === '00240' && Number(md.markdownPrice) === 2.4 && Number(md.originalPrice) === 4 && md.status === 'ACTIVE' && Number(md.remainingQty) === 3, md);
  const code = md.labelCode;
  const planAfter = data(call('GET', `${PRICING}/markdowns/plan?storeId=${store.id}`, { token: storekeeper.token }));
  truthy('the plan now shows the sticker on that batch', (planAfter.suggestions || []).some((s) => s.batchId === yog.batchId && s.existing && s.existing.id === md.id), planAfter);
  expect(call('GET', `${PRICING}/markdowns/${md.id}`, { token: rival.owner.token }), "a rival's owner does not read it", 404);
  truthy('the store lists one live sticker', (data(call('GET', `${PRICING}/markdowns?storeId=${store.id}&status=ACTIVE`, { token: storekeeper.token })) || []).length === 1);

  // ── the till ─────────────────────────────────────────────────────────────────
  const label = (token, c) => call('GET', `${PRICING}/prices/markdown-labels/${c}`, { token });
  const read = label(cashier.token, code);
  expect(read, 'the cashier scans the sticker', 200);
  truthy('and the till learns the markdown, the product and the price', data(read).markdownId === md.id && data(read).variantId === variantId && Number(data(read).markdownPrice) === 2.4 && Number(data(read).remainingQty) === 3, data(read));
  expect(label(rivalCashier.token, code), "a rival's till does not know the code", 404);
  expect(label(cashier.token, '2100009000009'), 'a code no sticker carries is unknown', 404, 'PRICING_MARKDOWN_LABEL_UNKNOWN');

  const place = (token, items, extra) => call('POST', '/api/order-svc/orders', { token, idem: true, body: Object.assign({ storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', currency: 'GBP', items }, extra || {}) });
  expect(place(cashier.token, [{ variantId: otherVariant, qty: 1, markdownId: md.id }]), "the sticker on another product's line is refused with why", 400, 'PRICING_MARKDOWN_VARIANT_MISMATCH');
  expect(place(cashier.token, [{ variantId, qty: 4, markdownId: md.id }]), 'four at a sticker for three is refused with why', 409, 'PRICING_MARKDOWN_EXHAUSTED');
  const sale = place(cashier.token, [{ variantId, qty: 2, markdownId: md.id }, { variantId, qty: 1 }]);
  expect(sale, 'two packs at the sticker and one at the list on one ticket', 201);
  const order = data(sale);
  const stickered = (order.items || []).find((i) => i.markdownId === md.id);
  const listed = (order.items || []).find((i) => !i.markdownId);
  truthy('the stickered line is at 2.40 and names the markdown; the other is at 4.00', stickered && Number(stickered.unitPrice) === 2.4 && Number(stickered.qty) === 2 && listed && Number(listed.unitPrice) === 4, order.items);
  truthy('the ticket comes to 8.80 before VAT', Number(order.subtotal) === 8.8, order.subtotal);
  const countedDown = poll(15, () => Number(data(label(cashier.token, code)).remainingQty) === 1);
  truthy('the sticker counts down to one within 15 s of the sale', countedDown >= 0, label(cashier.token, code).body);
  expect(place(cashier.token, [{ variantId, qty: 2, markdownId: md.id }]), 'two more at the sticker is one too many', 409, 'PRICING_MARKDOWN_EXHAUSTED');
  const last = place(cashier.token, [{ variantId, qty: 1, markdownId: md.id }]);
  expect(last, 'the last pack sells', 201);
  const soldOut = poll(15, () => label(cashier.token, code).status === 409);
  truthy('and the sticker is then sold out at the till', soldOut >= 0, label(cashier.token, code).body);
  expect(label(cashier.token, code), 'saying so', 409, 'PRICING_MARKDOWN_EXHAUSTED');
  const detail = data(call('GET', `${PRICING}/markdowns/${md.id}`, { token: storekeeper.token }));
  truthy('the markdown reads three stickered, three sold', Number(detail.redeemedQty) === 3 && Number(detail.remainingQty) === 0, detail);
  const staffRead = call('GET', `/api/order-svc/orders/${order.id}`, { token: owner });
  truthy('the order keeps which line was at a sticker', staffRead.status === 200 && (data(staffRead).items || []).some((i) => i.markdownId === md.id), staffRead.body);

  // ── taking the stickers off ───────────────────────────────────────────────────
  const second = data(sticker(storekeeper.token, Object.assign({}, draft, { qty: 2, percentOff: 50 })));
  truthy('a second sticker on the same batch takes the next code', second && second.labelCode !== code && /^21\d{11}$/.test(second.labelCode), second);
  const off = (token, id, body) => call('POST', `${PRICING}/markdowns/${id}/cancel`, { token, body });
  expect(off(cashier.token, second.id, { reason: 'wrong shelf' }), 'the till does not take stickers off', 403);
  expect(off(storekeeper.token, second.id, { reason: '' }), 'a reason is needed', 400);
  expect(off(rival.owner.token, second.id, { reason: 'x' }), "a rival's owner finds nothing to take off", 404);
  const taken = off(storekeeper.token, second.id, { reason: 'wrong shelf' });
  expect(taken, 'the storekeeper takes them off', 200);
  truthy('cancelled, with the reason', data(taken).status === 'CANCELLED' && data(taken).cancelReason === 'wrong shelf', data(taken));
  expect(off(storekeeper.token, second.id, { reason: 'again' }), 'a second time is refused', 409, 'PRICING_MARKDOWN_NOT_ACTIVE');
  expect(label(cashier.token, second.labelCode), 'the code no longer scans', 404, 'PRICING_MARKDOWN_LABEL_UNKNOWN');
  expect(place(cashier.token, [{ variantId, qty: 1, markdownId: second.id }]), 'and a sale naming it is refused with why', 409, 'PRICING_MARKDOWN_CANCELLED');
  truthy('the store lists it under taken off', (data(call('GET', `${PRICING}/markdowns?storeId=${store.id}&status=CANCELLED`, { token: storekeeper.token })) || []).some((m) => m.id === second.id));
  truthy("and the other store's list is empty", (data(call('GET', `${PRICING}/markdowns?storeId=${other.id}`, { token: owner })) || []).length === 0);
}
