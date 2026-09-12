// order-svc: the order lifecycle (place, pay, fulfil, return, cancel, void), receipts, the POS log,
// fiscal receipts, special orders, parked sales, no-sale, gift cards, layaways and the online
// shopper's view — with the refusals around each.
//
//   k6/run.sh order-crud
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  register,
  sellableVariant,
  truthy,
} from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  const tenant = onboardTenant('order', { stores: 1 });
  const rival = onboardTenant('order-rival', { stores: 1 });
  const storeId = tenant.stores[0].id;
  tenant.variantId = sellableVariant(tenant, 'Ordered mug').variantId;
  priceVariants(tenant, [tenant.variantId], '12.50');
  if (receive(tenant, storeId, tenant.variantId, 200).status !== 201) throw new Error('receive failed');
  return { tenant, rival, shopper: register('order-shopper'), stranger: register('order-stranger') };
}

export default function ({ tenant, rival, shopper, stranger }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;
  const sale = (extra = {}) => ({ storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 2 }], ...extra });
  const place = (body, opts = {}) => call('POST', '/api/order-svc/orders', { token: t, idem: true, body, ...opts });
  const pay = (order) =>
    call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId, currency: 'GBP' } });
  const statusOf = (id) => data(call('GET', `/api/order-svc/orders/${id}`, { token: t })).status;
  const parkedOnline = () =>
    call('POST', '/api/order-svc/orders', { token: shopper.token, storefront: tenant.tenantId, idem: true, body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] } });

  // ── placing orders ──────────────────────────────────────────────────────────
  expect(place({ ...sale(), channel: undefined }), '[-] order: channel required', 400);
  expect(place({ ...sale(), items: [{ variantId, qty: 0 }] }), '[-] order: quantity above zero', 400);
  expect(place(sale({ paymentMethod: 'BARTER' })), '[-] order: payment method must be known', 400, 'ORDER_PAYMENT_METHOD_INVALID');
  expect(place(sale({ fulfilmentType: 'DELIVERY' })), '[-] delivery needs an address', 400, 'ORDER_DELIVERY_ADDRESS_REQUIRED');
  expect(place(sale(), { token: shopper.token, storefront: tenant.tenantId }), '[-] a shopper cannot place a POS order', 403);
  expect(call('POST', '/api/order-svc/orders', { idem: true, body: sale() }), '[-] no token', 401);

  const exempt = place(sale({ taxExempt: true, exemptReason: 'Registered charity' }));
  expect(exempt, '[+] place a tax-exempt POS order', 201);
  // Only recorded: VAT still comes from pricing-svc's quote (VAT codes, customer VAT status).
  truthy('[+] tax exempt: flag and reason recorded, priced from the list', data(exempt).taxExempt === true && data(exempt).exemptReason === 'Registered charity' && Number(data(exempt).subtotal) === 25, data(exempt));

  const order = data(place(sale()));
  truthy('[+] a POS order starts PENDING', order.status === 'PENDING', order);
  expect(call('GET', `/api/order-svc/orders/${order.id}`, { token: t }), '[+] get order', 200);
  expect(call('GET', `/api/order-svc/orders/${order.id}`, { token: rival.owner.token }), "[-] a rival cannot read our order", 404);
  expect(call('GET', `/api/order-svc/orders/${UNKNOWN}`, { token: t }), '[-] unknown order', 404);
  const list = call('GET', `/api/order-svc/orders?store=${storeId}&channel=POS&limit=100`, { token: t });
  expect(list, '[+] list POS orders at the store', 200);
  truthy('[+] ...includes both', [order.id, data(exempt).id].every((id) => (data(list) || []).some((o) => o.id === id)), (data(list) || []).length);
  expect(call('GET', '/api/order-svc/orders', { token: shopper.token }), "[-] a shopper cannot list the tenant's orders", 403);

  // ── pay, fulfil, return ─────────────────────────────────────────────────────
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, body: { reason: 'Too early', items: [{ variantId, qty: 1 }] } }),
    '[-] return before the goods were handed over',
    409,
    'ORDER_CANNOT_RETURN'
  );
  expect(pay(order), '[+] take payment', [200, 201]);
  // A till sale is handed over the moment it is paid for.
  poll(30, () => statusOf(order.id) === 'FULFILLED');
  truthy('[+] paying for a till sale fulfils it', statusOf(order.id) === 'FULFILLED', statusOf(order.id));
  expect(call('POST', `/api/order-svc/orders/${order.id}/fulfil`, { token: t }), '[-] fulfil twice', 409, 'ORDER_NOT_FULFILLABLE');
  expect(call('POST', `/api/order-svc/orders/${order.id}/cancel`, { token: t, body: { reason: 'Too late' } }), '[-] cancel a fulfilled order', 409);
  expect(call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, body: { items: [{ variantId, qty: 1 }] } }), '[-] return: reason required', 400);
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, body: { reason: 'Too many', items: [{ variantId, qty: 5 }] } }),
    '[-] return more than was bought',
    [400, 409, 422]
  );
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, body: { reason: 'Chipped', refundMethod: 'CASH', items: [{ variantId, qty: 1, condition: 'DAMAGED' }] } }),
    '[+] return one mug',
    201
  );
  expect(call('GET', `/api/order-svc/orders/${order.id}/returns`, { token: t }), '[+] list returns', 200);
  const history = call('GET', `/api/order-svc/orders/${order.id}/history`, { token: t });
  expect(history, '[+] order history', 200);
  truthy('[+] history ends FULFILLED', JSON.stringify(data(history)).includes('FULFILLED'), data(history));

  // ── part-fulfilment (SJ-D35) ────────────────────────────────────────────────
  const onHand = () => {
    const rows = data(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${storeId}&limit=100`, { token: t })) || [];
    const row = rows.find((r) => r.variantId === variantId);
    return row ? Number(row.onHand) : NaN;
  };
  // The till sale (-2) and the returned mug (+1) above reach inventory-svc asynchronously; wait for
  // the level to settle so what follows measures the part-fulfilment and nothing else.
  truthy('[+] inventory settled after the till sale and the return', poll(30, () => onHand() === 200 - 2 + 1) >= 0, onHand());
  const before = onHand();
  const partial = data(place({ storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 4 }] }));
  expect(call('POST', `/api/order-svc/orders/${partial.id}/confirm`, { token: t, body: {} }), '[+] confirm a four-mug pickup order', 200);
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId, qty: 5 }] } }),
    '[-] hand over more than was ordered',
    409,
    'ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING'
  );
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId: UNKNOWN, qty: 1 }] } }),
    '[-] hand over a line that is not on the order',
    400,
    'ORDER_FULFIL_LINE_UNKNOWN'
  );
  const one = call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId, qty: 1 }] } });
  expect(one, '[+] hand over one of four', 200);
  truthy('[+] the order is PARTIALLY_FULFILLED with one handed over', data(one).status === 'PARTIALLY_FULFILLED' && Number(data(one).items[0].fulfilledQty) === 1, data(one));
  expect(call('POST', `/api/order-svc/orders/${partial.id}/cancel`, { token: t, body: { reason: 'changed mind' } }), '[-] cancel once goods have gone out', 409, 'ORDER_PARTLY_FULFILLED');
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/returns`, { token: t, body: { reason: 'Too many', refundMethod: 'CASH', items: [{ variantId, qty: 2 }] } }),
    '[-] return two when only one was handed over',
    409,
    'RETURN_QTY_EXCEEDS_PURCHASED'
  );
  truthy('[+] inventory deducted exactly the one that left', poll(30, () => onHand() === before - 1) >= 0, { before, now: onHand() });
  const rest = call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t });
  expect(rest, '[+] hand over the rest with no body', 200);
  truthy('[+] now FULFILLED, four of four', data(rest).status === 'FULFILLED' && Number(data(rest).items[0].fulfilledQty) === 4, data(rest));
  truthy('[+] inventory deducted the other three, and nothing twice', poll(30, () => onHand() === before - 4) >= 0, { before, now: onHand() });
  expect(call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t }), '[-] nothing left to hand over', 409, 'ORDER_NOT_FULFILLABLE');
  const partialHistory = JSON.stringify(data(call('GET', `/api/order-svc/orders/${partial.id}/history`, { token: t })));
  truthy('[+] the history says how much went when', partialHistory.includes('part-fulfilled: 1 of 4'), partialHistory);

  // ── cancel and void ─────────────────────────────────────────────────────────
  const toCancel = data(place(sale()));
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: {} }), '[-] cancel: a body must give a reason', 400);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: { reason: 'Customer walked out' } }), '[+] cancel a pending order', 200);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/fulfil`, { token: t }), '[-] fulfil a cancelled order', 409, 'ORDER_NOT_FULFILLABLE');
  expect(
    call('POST', `/api/order-svc/orders/${toCancel.id}/returns`, { token: t, body: { reason: 'Never had it', items: [{ variantId, qty: 1 }] } }),
    '[-] return a cancelled order',
    409,
    'ORDER_CANNOT_RETURN'
  );
  const toVoid = data(place(sale()));
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, body: {} }), '[-] void: reason required', 400);
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, body: { reason: 'Rang up twice' } }), '[+] void a POS order', 200);
  truthy('[+] ...VOIDED', statusOf(toVoid.id) === 'VOIDED', statusOf(toVoid.id));
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, body: { reason: 'Again' } }), '[-] void twice', 409);

  // ── receipts, POS log, fiscal receipt ──────────────────────────────────────
  const receipts = `/api/order-svc/admin/orders/${order.id}/receipts`;
  expect(call('POST', receipts, { token: t, body: { receiptType: 'PRINT', printCount: 1 } }), '[+] print a receipt', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'EMAIL', emailedTo: 'customer@example.com' } }), '[+] email a receipt', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'EMAIL' } }), '[-] email receipt: address required', 400);
  expect(call('GET', receipts, { token: t }), '[+] list receipts', 200);
  expect(call('POST', `/api/order-svc/admin/orders/${UNKNOWN}/receipts`, { token: t, body: { receiptType: 'PRINT' } }), '[-] receipt for an unknown order', 404);

  const logged = call('POST', `/api/order-svc/pos/log/orders/${order.id}`, { token: t });
  expect(logged, '[+] write the POS log entry', 201);
  // The till calls this right after taking money, so it is on the retry path: a repeat is the same entry.
  const relogged = call('POST', `/api/order-svc/pos/log/orders/${order.id}`, { token: t });
  truthy('[+] writing it again returns the same entry', relogged.status === 201 && data(relogged).id === data(logged).id, { first: data(logged).id, again: data(relogged).id });
  expect(call('POST', `/api/order-svc/pos/log/orders/${data(parkedOnline()).id}`, { token: t }), '[-] POS log is for POS orders only', 400, 'POSLOG_NOT_POS');
  expect(call('POST', `/api/order-svc/pos/log/orders/${UNKNOWN}`, { token: t }), '[-] POS log for an unknown order', 404);
  expect(call('GET', `/api/order-svc/admin/pos-log?storeId=${storeId}`, { token: t }), '[+] list the POS log', 200);
  expect(call('GET', `/api/order-svc/admin/pos-log/orders/${order.id}`, { token: t }), '[+] POS log for the order', 200);

  const fiscal = call('POST', `/api/order-svc/admin/orders/${order.id}/fiscal-receipt`, { token: t });
  expect(fiscal, '[+] issue the fiscal receipt', 200);
  const again = call('POST', `/api/order-svc/admin/orders/${order.id}/fiscal-receipt`, { token: t });
  truthy('[+] issuing again returns the same number', again.status === 200 && JSON.stringify(data(again)) === JSON.stringify(data(fiscal)), { first: data(fiscal), again: data(again) });
  expect(call('GET', `/api/order-svc/orders/${order.id}/fiscal-receipt`, { token: t }), '[+] read the fiscal receipt', 200);
  expect(call('GET', `/api/order-svc/admin/fiscal-receipts?storeId=${storeId}`, { token: t }), '[+] list fiscal receipts', 200);
  expect(call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${storeId}`, { token: t }), '[+] fiscal receipt audit', 200);
  expect(call('GET', '/api/order-svc/admin/fiscal-receipts', { token: t }), '[-] fiscal receipts: store required', 400);

  // ── special orders ──────────────────────────────────────────────────────────
  const special = { storeId, customerName: 'Jane Doe', customerEmail: 'jane@example.com', deliveryAddress: '123 High Street', requestedDeliveryDate: '2027-07-01', items: [{ variantId, qty: 1, unitPrice: 49.99 }], currency: 'GBP' };
  expect(call('POST', '/api/order-svc/admin/special-orders', { token: t, body: { ...special, storeId: undefined } }), '[-] special order: store required', 400);
  const so = call('POST', '/api/order-svc/admin/special-orders', { token: t, body: special });
  expect(so, '[+] create a special order', 201);
  const soId = data(so).id;
  expect(call('GET', '/api/order-svc/admin/special-orders', { token: t }), '[+] list special orders', 200);
  expect(call('GET', `/api/order-svc/admin/special-orders/${soId}`, { token: t }), '[+] get special order', 200);
  expect(call('GET', `/api/order-svc/admin/special-orders/${soId}`, { token: rival.owner.token }), "[-] a rival cannot read it", 404);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/fulfil`, { token: t }), '[-] fulfil before confirming', 409);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/confirm`, { token: t }), '[+] confirm special order', 200);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/fulfil`, { token: t }), '[+] fulfil special order', 200);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/cancel`, { token: t }), '[-] cancel a fulfilled special order', 409);
  expect(call('POST', '/api/order-svc/admin/special-orders', { token: shopper.token, body: special }), '[-] a customer cannot create special orders', 403);

  // ── parked sales and no-sale ────────────────────────────────────────────────
  const parked = call('POST', '/api/order-svc/pos/parked-sales', { token: t, body: { storeId, customerName: 'Queue 2', items: [{ variantId, qty: 1, unitPrice: 12.5 }] } });
  expect(parked, '[+] park a sale', 201);
  expect(call('POST', '/api/order-svc/pos/parked-sales', { token: t, body: { storeId } }), '[-] park: items required', 400);
  expect(call('GET', `/api/order-svc/pos/parked-sales?storeId=${storeId}`, { token: t }), '[+] list parked sales', 200);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[+] get parked sale', 200);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: rival.owner.token }), "[-] a rival cannot see it", 404);
  expect(call('DELETE', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[+] discard parked sale', [200, 204]);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[-] a discarded sale is gone', 404);
  expect(call('POST', '/api/order-svc/pos/no-sale', { token: t, body: { storeId, reason: 'Change for the float' } }), '[+] open the drawer for no sale', 201);
  expect(call('POST', '/api/order-svc/pos/no-sale', { token: shopper.token, body: { storeId } }), '[-] a customer cannot open the drawer', 403);

  // ── gift cards ──────────────────────────────────────────────────────────────
  const card = call('POST', '/api/order-svc/gift-cards', { token: t, body: { storeId, amount: 50, currency: 'GBP' } });
  expect(card, '[+] issue a £50 gift card', 201);
  const code = data(card).code;
  expect(call('POST', '/api/order-svc/gift-cards', { token: t, body: { storeId, amount: 0 } }), '[-] gift card: amount above zero', 400);
  expect(call('GET', `/api/order-svc/gift-cards/${code}`, { token: t }), '[+] check a gift card', 200);
  expect(call('GET', `/api/order-svc/gift-cards/${code}`, { token: rival.owner.token }), "[-] another tenant's card is unknown", 404);
  expect(call('POST', `/api/order-svc/gift-cards/${code}/redeem`, { token: t, body: { amount: 20, orderId: order.id } }), '[+] redeem £20', 200);
  expect(call('POST', `/api/order-svc/gift-cards/${code}/redeem`, { token: t, body: { amount: 31 } }), '[-] redeem more than the balance', [409, 422]);
  expect(call('POST', `/api/order-svc/gift-cards/${code}/reload`, { token: t, body: { amount: 10 } }), '[+] reload £10', 200);
  const balance = data(call('GET', `/api/order-svc/gift-cards/${code}`, { token: t }));
  truthy('[+] balance is 50 - 20 + 10', Number(balance.currentBalance) === 40 && Number(balance.initialBalance) === 50, balance);
  const tx = call('GET', `/api/order-svc/gift-cards/${code}/transactions`, { token: t });
  expect(tx, '[+] gift card transactions', 200);
  truthy('[+] ...issue, redeem and reload', (data(tx) || []).length >= 3, data(tx));
  expect(call('GET', '/api/order-svc/gift-cards/NO-SUCH-CARD', { token: t }), '[-] unknown gift card', 404);

  // ── layaways ────────────────────────────────────────────────────────────────
  const lay = { storeId, items: [{ variantId, qty: 2, unitPrice: 12.5 }], initialDeposit: 5, paymentMethod: 'CASH', dueDate: '2027-01-31T00:00:00Z' };
  expect(call('POST', '/api/order-svc/layaways', { token: t, body: { ...lay, initialDeposit: 0 } }), '[-] layaway: deposit above zero', 400);
  expect(call('POST', '/api/order-svc/layaways', { token: t, body: { ...lay, dueDate: '31/01/2027' } }), '[-] layaway: due date is an ISO instant', 400, 'INVALID_DATE');
  const layaway = call('POST', '/api/order-svc/layaways', { token: t, body: lay });
  expect(layaway, '[+] start a layaway with £5 down', 201);
  const layId = data(layaway).id;
  expect(call('POST', `/api/order-svc/layaways/${layId}/complete`, { token: t }), '[-] complete before it is paid for', [400, 409, 422]);
  expect(call('POST', `/api/order-svc/layaways/${layId}/deposits`, { token: t, body: { amount: 20, paymentMethod: 'CASH' } }), '[+] pay the rest', [200, 201]);
  expect(call('POST', `/api/order-svc/layaways/${layId}/complete`, { token: t }), '[+] complete the layaway', 200);
  // Like order transitions, a layaway not in the state the action needs answers "not found or not active".
  expect(call('POST', `/api/order-svc/layaways/${layId}/cancel`, { token: t, body: { reason: 'Changed mind' } }), '[-] cancel a completed layaway', 404, 'LAYAWAY_NOT_FOUND');
  expect(call('GET', `/api/order-svc/layaways/${layId}`, { token: t }), '[+] get layaway', 200);
  expect(call('GET', `/api/order-svc/layaways/${layId}`, { token: rival.owner.token }), "[-] a rival cannot see it", 404);

  // ── the online shopper ──────────────────────────────────────────────────────
  const online = call('POST', '/api/order-svc/orders', {
    token: shopper.token,
    storefront: tenant.tenantId,
    idem: true,
    body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], contactPhone: '+447700900123' },
  });
  expect(online, '[+] a shopper places a pickup order', 201);
  const onlineId = data(online).id;
  const mine = call('GET', '/api/order-svc/orders/mine', { token: shopper.token, storefront: tenant.tenantId });
  expect(mine, '[+] my orders', 200);
  truthy('[+] ...include it', (data(mine) || []).some((o) => o.id === onlineId), data(mine));
  truthy("[-] another shopper's orders do not include it", !(data(call('GET', '/api/order-svc/orders/mine', { token: stranger.token, storefront: tenant.tenantId })) || []).some((o) => o.id === onlineId));

  // ── store reports ───────────────────────────────────────────────────────────
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-hour?storeId=${storeId}`, { token: t }), '[+] sales by hour', 200);
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-staff?storeId=${storeId}`, { token: t }), '[+] sales by staff', 200);
  expect(call('GET', `/api/order-svc/admin/reports/exceptions?storeId=${storeId}`, { token: t }), '[+] exceptions report (voids, returns)', 200);
  expect(call('GET', `/api/order-svc/admin/pos/stock-positions?storeId=${storeId}`, { token: t }), '[+] POS stock positions', 200);
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-hour?storeId=${storeId}`, { token: shopper.token }), '[-] a customer cannot read store reports', 403);
}
