// Usage metering and quotas (21.10), through the gateway: the platform prices a plan by what a
// business does — orders taken, texts sent — with some included each period and a price for each
// beyond; a business on it takes orders and sends texts, and tenant-svc counts them from the events
// of the services that did them, once each; the owner reads this period against the plan, the 80%
// and 100% marks each raised once; a marketing text past a hard ceiling is refused while a text a
// customer must get still goes; and the billing run bills the period's overage in arrears on the same
// invoice as the next period in advance, once. Refused: a hard ceiling on orders, a meter nobody
// counts, a negative allowance, a business pricing the platform, a cashier or a shopper reading the
// usage, an allowance asked for nothing or for a meter that does not exist; abuse: ten orders at
// once and one order retried ten times, counted exactly, and another business's count untouched.
//
//   k6/run.sh usage-metering-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  newKey,
  platformAdmin,
  poll,
  register,
  sellingTenant,
  truthy,
  uniq,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '5m',
};

const PLANS = '/api/tenant-svc/platform/plans';
const BILLING = '/api/tenant-svc/platform/billing';
const MINE = '/api/tenant-svc/admin/tenant/billing';
const USAGE = '/api/tenant-svc/admin/tenant/usage';
const SEND = '/api/notification-svc/notifications/send';

export function setup() {
  const admin = platformAdmin();
  const root = admin.token;
  const tag = uniq().toUpperCase().slice(0, 8);
  // The platform must say who it invoices as before anything is billed; a sweep has usually done so.
  if (call('GET', `${BILLING}/profile`, { token: root }).status !== 200) {
    must(call('PUT', `${BILLING}/profile`, {
      token: root,
      body: { legalName: `StoreQL Platform ${tag} Ltd`, addressLine1: '1 Quay Street', city: 'London', postcode: 'EC1A 1BB', country: 'GB', vatNumber: `GB${tag}`, invoicePrefix: 'INV', paymentTermsDays: 14, taxRate: '0.2000' },
    }), 200, 'seller');
  }
  // A plan that meters: three orders and two text parts a month included, then 0.50 and 0.035.
  const previous = (data(call('GET', PLANS, { token: root })) || []).find((p) => p.isDefault);
  const plan = must(call('POST', PLANS, { token: root, body: { code: `USAGE-${tag}`, name: 'Metered', billingInterval: 'MONTH', trialDays: 0, isPublic: true, sortOrder: 9 } }), 201, 'plan');
  must(call('POST', `${PLANS}/${plan.id}/prices`, { token: root, body: { currency: 'GBP', amount: 10 } }), 200, 'price');
  must(call('PUT', `${PLANS}/${plan.id}/meters`, { token: root, body: { meters: [{ meter: 'ORDERS', included: 3 }, { meter: 'SMS', included: 2, hard: true }] } }), 200, 'meters');
  must(call('POST', `${PLANS}/${plan.id}/meter-prices`, { token: root, body: { meter: 'ORDERS', currency: 'GBP', unitAmount: 0.5 } }), 200, 'order price');
  must(call('POST', `${PLANS}/${plan.id}/meter-prices`, { token: root, body: { meter: 'SMS', currency: 'GBP', unitAmount: 0.035 } }), 200, 'text price');
  must(call('POST', `${PLANS}/${plan.id}/activate`, { token: root }), 200, 'sell');
  must(call('POST', `${PLANS}/${plan.id}/default`, { token: root }), 200, 'default');
  const s = sellingTenant('usage', { price: '5.00' });
  // Put the platform back as it was: this flow's plan is for its own two businesses.
  if (previous) call('POST', `${PLANS}/${previous.id}/default`, { token: root });
  return { ...s, root, planId: plan.id, shopper: register('usage-shopper') };
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier, root, planId, shopper }) {
  const owner = tenant.owner.token;
  const shop = { storefront: tenant.tenantId };
  const usage = (token = owner) => data(call('GET', USAGE, { token }));
  const meter = (u, key) => ((u.meters || []).find((m) => m.meter === key)) || {};
  const waitFor = (key, n) => {
    let u = {};
    poll(60, () => {
      u = usage();
      return meter(u, key).used === n;
    });
    return u;
  };
  const order = (idem) => call('POST', '/api/order-svc/orders', {
    token: owner, idem, body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] },
  });

  // ── the plan, as the business reads it ──────────────────────────────────────────────────────────
  const start = usage();
  truthy('[+] a business on a metered plan reads this period against it', meter(start, 'ORDERS').included === 3 && meter(start, 'ORDERS').used === 0 && meter(start, 'SMS').hard === true && !!start.periodStart, start);
  expect(call('GET', USAGE, { token: cashier.token }), '[-] a cashier does not read what the business used', 403);
  expect(call('GET', USAGE, { token: shopper.token, ...shop }), '[-] nor a shopper', [401, 403]);
  expect(call('GET', USAGE, {}), '[-] nor anyone without a token', 401);
  const sk = call('GET', `${USAGE}/allowance?meter=SMS&quantity=1`, { token: storekeeper.token });
  expect(sk, '[+] the staff identity a service reads under is told whether one more text may go', 200);
  truthy('[+] ...and it may', data(sk).allowed === true, data(sk));
  expect(call('GET', `${USAGE}/allowance?meter=FAXES`, { token: storekeeper.token }), '[-] a meter nobody counts', 400, 'USAGE_METER_UNKNOWN');
  expect(call('GET', `${USAGE}/allowance?meter=SMS&quantity=0`, { token: storekeeper.token }), '[-] an allowance for nothing', 400, 'USAGE_QUANTITY_INVALID');

  // ── orders, counted from order-svc's events, once each ──────────────────────────────────────────
  const firstKey = newKey('usage-order');
  expect(order(firstKey), '[+] the shop takes an order', 201);
  for (let i = 0; i < 3; i++) expect(order(true), `[+] and another (${i + 2} of 4)`, 201);
  expect(order(firstKey), '[+] the first, retried with its key, is the same order', [200, 201]);
  let u = waitFor('ORDERS', 4);
  const orders = meter(u, 'ORDERS');
  truthy('[+] four orders counted, not five: one beyond the three included', orders.used === 4 && orders.over === 1, orders);
  truthy('[+] ...0.50 owed beyond the plan so far', Number(orders.estimate) === 0.5, orders);
  truthy('[+] 80% and then 100% were each raised once', (u.alerts || []).filter((a) => a.meter === 'ORDERS').map((a) => a.threshold).sort().join() === '100,80', u.alerts);
  truthy('[+] and an order is never refused, however far over', data(call('GET', `${USAGE}/allowance?meter=ORDERS&quantity=1`, { token: storekeeper.token })).allowed === true);

  // ── abuse: ten at once, and one retried ten times ──────────────────────────────────────────────
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` };
  const body = JSON.stringify({ storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] });
  const burst = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}/api/order-svc/orders`, body, { headers: { ...headers, 'Idempotency-Key': newKey('usage-burst') }, tags: { name: 'POST /api/order-svc/orders (burst)' } }]));
  const sameKey = newKey('usage-same');
  const retried = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}/api/order-svc/orders`, body, { headers: { ...headers, 'Idempotency-Key': sameKey }, tags: { name: 'POST /api/order-svc/orders (retried)' } }]));
  truthy('[abuse] ten orders at once are ten orders', burst.every((r) => r.status === 201), burst.map((r) => r.status).join(','));
  truthy('[abuse] one order retried ten times at once is taken or told it is in flight', retried.every((r) => [200, 201, 409].includes(r.status)) && retried.some((r) => r.status === 201 || r.status === 200), retried.map((r) => r.status).join(','));
  u = waitFor('ORDERS', 15);
  truthy('[abuse] ...and counted as fifteen in all, exactly', meter(u, 'ORDERS').used === 15 && meter(u, 'ORDERS').over === 12, meter(u, 'ORDERS'));
  truthy('[abuse] with no third alert', (u.alerts || []).filter((a) => a.meter === 'ORDERS').length === 2, u.alerts);
  truthy('[abuse] and another business counted none of it', meter(usage(rival.owner.token), 'ORDERS').used === 0);

  // ── texts: counted in the parts the carrier bills ──────────────────────────────────────────────
  expect(call('POST', SEND, { token: owner, body: { channel: 'SMS', recipient: '+447700900555', subject: 'Ready', body: 'A'.repeat(200), type: 'ORDER_READY' } }), '[+] a 200-character text goes', 202);
  u = waitFor('SMS', 2);
  truthy('[+] ...and counts as two parts, all two the plan includes', meter(u, 'SMS').used === 2, meter(u, 'SMS'));
  truthy('[+] so one more marketing text may not go', data(call('GET', `${USAGE}/allowance?meter=SMS&quantity=1`, { token: storekeeper.token })).allowed === false);

  // A shopper who agreed to marketing by text: the quota is the only thing that stops this one.
  const me = must(call('POST', '/api/customer-svc/customers/me', { token: shopper.token, ...shop }), 200, 'claim');
  must(call('PUT', '/api/customer-svc/customers/me/marketing', { token: shopper.token, ...shop, body: { channels: [{ channel: 'SMS', granted: true }], notice: 'Offers by text' } }), 200, 'consent');
  const offer = call('POST', SEND, { token: owner, body: { channel: 'SMS', recipient: '+447700900556', subject: 'Offer', body: '20% off this weekend', category: 'MARKETING', customerId: me.id } });
  expect(offer, '[-] a marketing text past the hard ceiling is refused', 409, 'USAGE_QUOTA_REACHED');
  expect(call('POST', SEND, { token: owner, body: { channel: 'SMS', recipient: '+447700900555', subject: 'Ready', body: 'Your order is ready.', type: 'ORDER_READY' } }), '[+] but a text a customer must get still goes', 202);
  u = waitFor('SMS', 3);
  truthy('[+] ...counted, one part beyond the allowance', meter(u, 'SMS').over === 1, meter(u, 'SMS'));
  truthy('[-] another business\'s ceiling is its own', data(call('GET', `${USAGE}/allowance?meter=SMS&quantity=1`, { token: rival.owner.token })).allowed === true);

  // ── billed in arrears, beside the next period, once ────────────────────────────────────────────
  const periodEnd = data(call('GET', MINE, { token: owner })).subscription.periodEnd;
  expect(call('POST', `${BILLING}/run?asOf=${periodEnd}`, { token: root }), '[+] the billing run reaches the end of the period', 200);
  expect(call('POST', `${BILLING}/run?asOf=${periodEnd}`, { token: root }), '[+] and runs again', 200);
  const renewals = (data(call('GET', `${MINE}/invoices?limit=50`, { token: owner })) || []).filter((i) => i.kind === 'PERIOD' && i.periodStart === periodEnd);
  truthy('[+] one invoice for the next period, however often the run ran', renewals.length === 1, renewals);
  const lines = (data(call('GET', `${MINE}/invoices/${(renewals[0] || {}).id}`, { token: owner })).lines) || [];
  const byKind = (kind) => lines.filter((l) => l.kind === kind);
  const orderLine = byKind('USAGE').find((l) => l.description.startsWith('Orders taken')) || {};
  const textLine = byKind('USAGE').find((l) => l.description.startsWith('Text messages')) || {};
  truthy('[+] it bills the next period in advance', byKind('PLAN').length === 1 && Number(byKind('PLAN')[0].amount) === 10, lines);
  truthy('[+] and the twelve orders beyond the plan in arrears, at 0.50', Number(orderLine.quantity) === 12 && Number(orderLine.amount) === 6, orderLine);
  truthy('[+] and the text part beyond it at 0.035, to the tenth of a penny', Number(textLine.quantity) === 1 && Number(textLine.unitAmount) === 0.035, textLine);
  const history = usage().history || [];
  truthy('[+] the period is written down against the invoice that billed it', history.length === 2 && history.every((h) => h.invoiceId === (renewals[0] || {}).id), history);

  // ── the platform's view, and what it keeps to itself ───────────────────────────────────────────
  expect(call('GET', `${BILLING}/tenants/${tenant.tenantId}/usage`, { token: root }), '[+] the platform reads one business\'s usage', 200);
  truthy('[+] and sees who is outgrowing a plan', (data(call('GET', `${BILLING}/usage-alerts?limit=200`, { token: root })) || []).some((a) => a.tenantId === tenant.tenantId && a.threshold === 100));
  expect(call('GET', `${BILLING}/usage-alerts`, { token: owner }), '[-] a business does not read the platform\'s list', 403);
  expect(call('PUT', `${PLANS}/${planId}/meters`, { token: root, body: { meters: [{ meter: 'ORDERS', included: 3, hard: true }] } }), '[-] an order is never a hard ceiling', 400, 'PLAN_METER_NOT_REFUSABLE');
  expect(call('PUT', `${PLANS}/${planId}/meters`, { token: root, body: { meters: [{ meter: 'API_CALLS', included: 3 }] } }), '[-] nor is something nobody counts', 400, 'PLAN_METER_UNKNOWN');
  expect(call('PUT', `${PLANS}/${planId}/meters`, { token: root, body: { meters: [{ meter: 'SMS', included: -1 }] } }), '[-] nor a negative allowance', 400, 'PLAN_METER_INCLUDED_INVALID');
  expect(call('PUT', `${PLANS}/${planId}/meters`, { token: owner, body: { meters: [] } }), '[-] a business does not price the platform', 403);
  truthy('[-] and none of that changed the plan', (data(call('GET', `${PLANS}/${planId}`, { token: root })).meters || []).length === 2);

  completed.add(1);
}
