// reporting-svc: inventory and sales reports, fed by real stock movements and a paid POS sale and
// read back once the events have been projected.
//
//   k6/run.sh reporting-crud
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
  staffUser,
  truthy,
} from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const INVENTORY = '/api/reporting-svc/admin/reports/inventory';
const SALES = '/api/reporting-svc/admin/reports/sales';

export function setup() {
  const tenant = onboardTenant('report', { country: 'IN', currency: 'INR', stores: 1 });
  const rival = onboardTenant('report-rival', { stores: 1 });
  tenant.variantId = sellableVariant(tenant, 'Reported rice').variantId;
  priceVariants(tenant, [tenant.variantId], '80.00');
  tenant.keeper = staffUser(tenant, 'STOREKEEPER', [tenant.stores[0].id]);
  return { tenant, rival };
}

function rowsOf(res) {
  return data(res).rows || [];
}

export default function ({ tenant, rival }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;

  // ── make something to report ───────────────────────────────────────────────
  expect(receive(tenant, storeId, variantId, 100), '[+] receive 100', 201);
  expect(
    call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, body: { storeId, variantId, delta: -5, reason: 'breakage' } }),
    '[+] write off 5',
    200
  );
  const order = call('POST', '/api/order-svc/orders', {
    token: t,
    idem: true,
    body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 2 }] },
  });
  expect(order, '[+] ring up 2 at 80', 201);
  expect(
    call('POST', '/api/payment-svc/payments', {
      token: t,
      idem: true,
      body: { orderId: data(order).id, amount: data(order).total, method: 'CASH', storeId, currency: 'INR' },
    }),
    '[+] take cash for it',
    [200, 201]
  );

  // ── inventory reports ──────────────────────────────────────────────────────
  let row = null;
  poll(60, () => {
    row = rowsOf(call('GET', `${INVENTORY}/on-hand?storeId=${storeId}&variantId=${variantId}`, { token: t })).find((r) => r.variantId === variantId);
    return row && Number(row.onHand) === 95;
  });
  truthy('[+] on-hand shows 100 received less 5 written off', row && Number(row.onHand) === 95, row);
  const onHand = call('GET', `${INVENTORY}/on-hand`, { token: t });
  expect(onHand, '[+] on-hand for the tenant', 200);
  truthy('[+] on-hand grand total counts it', Number(data(onHand).grandTotal) >= 95, data(onHand));
  expect(call('GET', `${INVENTORY}/on-hand?storeId=nope`, { token: t }), '[-] on-hand: store filter must be a UUID', 400);

  const netting = call('GET', `${INVENTORY}/supply-demand?variantId=${variantId}`, { token: t });
  expect(netting, '[+] supply and demand', 200);
  truthy('[+] supply and demand has the variant', rowsOf(netting).some((r) => r.variantId === variantId), data(netting));

  let weekly = [];
  poll(60, () => {
    weekly = rowsOf(call('GET', `${INVENTORY}/movement-stats?variantId=${variantId}`, { token: t }));
    return weekly.some((r) => Number(r.totalIn) >= 100);
  });
  truthy('[+] movement stats: 100 in this week', weekly.some((r) => Number(r.totalIn) >= 100 && Number(r.totalOut) >= 5), weekly);
  expect(call('GET', `${INVENTORY}/movement-stats?bucketDays=1`, { token: t }), '[+] movement stats by day', 200);
  expect(call('GET', `${INVENTORY}/movement-stats?bucketDays=30`, { token: t }), '[+] movement stats by month', 200);
  // Out-of-range buckets (below 1 or above 365 days) fall back to a week rather than failing.
  const fallback = call('GET', `${INVENTORY}/movement-stats?bucketDays=0&variantId=${variantId}`, { token: t });
  expect(fallback, '[-] movement stats: a bucket of 0 days falls back to weekly', 200);
  truthy('[-] ...with the same weekly rows', rowsOf(fallback).length === weekly.length, rowsOf(fallback));

  // ── sales reports ──────────────────────────────────────────────────────────
  let inr = null;
  poll(60, () => {
    inr = rowsOf(call('GET', `${SALES}/summary`, { token: t })).find((r) => r.currency === 'INR');
    return inr && inr.orders >= 1;
  });
  truthy('[+] sales summary: one INR sale of 160', inr && inr.orders === 1 && Number(inr.gross) >= 160, inr);
  const pos = call('GET', `${SALES}/summary?channel=POS&storeId=${storeId}`, { token: t });
  expect(pos, '[+] sales summary for POS at the store', 200);
  truthy('[+] ...includes the sale', rowsOf(pos).some((r) => r.orders >= 1), data(pos));
  const days = call('GET', `${SALES}/by-day`, { token: t });
  expect(days, '[+] sales by day', 200);
  truthy('[+] ...today has the sale', rowsOf(days).some((r) => r.orders >= 1), data(days));
  expect(call('GET', `${SALES}/summary?from=yesterday`, { token: t }), '[-] sales summary: from must be a date', 400);

  // ── who may read reports ───────────────────────────────────────────────────
  truthy("[-] a rival's on-hand has none of our stock", !rowsOf(call('GET', `${INVENTORY}/on-hand`, { token: rival.owner.token })).some((r) => r.variantId === variantId));
  truthy("[-] a rival's sales summary is empty", rowsOf(call('GET', `${SALES}/summary`, { token: rival.owner.token })).every((r) => r.orders === 0));
  expect(call('GET', `${INVENTORY}/on-hand`, { token: tenant.keeper.token }), '[-] a storekeeper cannot read management reports', 403);
  expect(call('GET', `${SALES}/summary`, { token: register('report-shopper').token }), '[-] a customer cannot read reports', 403);
  expect(call('GET', `${SALES}/summary`), '[-] no token', 401);
}
