// Cash refused at the limit the law sets where the store trades (09.17), through the gateway: the
// register lists the limits that reach a country with their status; a French till refuses cash of
// EUR 1,000 or more in one payment or in pieces for the same goods and takes the balance by card;
// a German till takes any cash today with the Union's EUR 10,000 shown as upcoming; an Indian till
// refuses two lakh rupees; a British till has no limit. Refused: the same goods paid in pieces
// that together reach the limit, exactly the limit, a cashier trying twice; abuse: a rival tenant's
// sheet, cash in a currency the limit does not name.
//
//   k6/run.sh cash-limit-flow
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  sellingTenant,
  truthy,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '5m',
};

const OBL = '/api/tenant-svc/admin/tenant/obligations';
const PAY = '/api/payment-svc/payments';
const O = '/api/order-svc/orders';

export function setup() {
  const fr = sellingTenant('cash-fr', { country: 'FR', currency: 'EUR', price: '600.00' });
  const de = sellingTenant('cash-de', { country: 'DE', currency: 'EUR', price: '600.00' });
  const india = sellingTenant('cash-in', { country: 'IN', currency: 'INR', price: '125000.00' });
  const gb = sellingTenant('cash-gb', { country: 'GB', currency: 'GBP', price: '600.00' });
  return { fr, de, india, gb };
}

const limits = (res) => ((data(res) || {}).cashLimits || []);
const limit = (res, scope) => limits(res).find((l) => l.scope === scope) || {};

export default function ({ fr, de, india, gb }) {
  // A till sale of two units, unpaid, then paid as the test says.
  const sale = (t, qty = 2) =>
    must(
      call('POST', O, { token: t.cashier.token, idem: true, body: { storeId: t.store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: t.variantId, qty }] } }),
      201,
      'till sale'
    );
  const pay = (t, order, amount, method, currency, token = t.cashier.token) =>
    call('POST', PAY, { token, idem: true, body: { orderId: order.id, amount, method, storeId: t.store.id, currency } });

  // ── the register ─────────────────────────────────────────────────────────────────────────────────
  const sheet = call('GET', OBL, { token: fr.tenant.owner.token });
  expect(sheet, "[+] a French business's sheet carries the cash limits that reach France", 200);
  truthy('[+] ...its own EUR 1,000 in force, with the instrument', limit(sheet, 'FR').currency === 'EUR' && Number(limit(sheet, 'FR').fromAmount) === 1000 && limit(sheet, 'FR').status === 'IN_FORCE' && /L112-6/.test(limit(sheet, 'FR').citation), limits(sheet));
  truthy("[+] ...and the Union's EUR 10,000 upcoming from 10 July 2027", Number(limit(sheet, 'EU').fromAmount) === 10000 && limit(sheet, 'EU').status === 'UPCOMING' && limit(sheet, 'EU').effectiveFrom === '2027-07-10', limit(sheet, 'EU'));
  const later = call('GET', `${OBL}?on=2027-07-10`, { token: fr.tenant.owner.token });
  truthy('[+] ...asked about that day, in force', limit(later, 'EU').status === 'IN_FORCE', limit(later, 'EU'));
  const german = call('GET', OBL, { token: de.tenant.owner.token });
  truthy('[+] a German business has only the upcoming Union cap', limits(german).length === 1 && limit(german, 'EU').status === 'UPCOMING', limits(german));
  const indian = call('GET', OBL, { token: india.tenant.owner.token });
  truthy("[+] an Indian business has India's two lakh rupees, in rupees", Number(limit(indian, 'IN').fromAmount) === 200000 && limit(indian, 'IN').currency === 'INR' && /269ST/.test(limit(indian, 'IN').citation), limits(indian));
  truthy('[+] a British business has none', limits(call('GET', OBL, { token: gb.tenant.owner.token })).length === 0, 'GB');
  expect(call('GET', OBL, { token: fr.cashier.token }), '[+] a cashier reads the sheet too', 200);

  // ── the French till ──────────────────────────────────────────────────────────────────────────────
  const big = sale(fr);
  const refused = pay(fr, big, big.total, 'CASH', 'EUR');
  expect(refused, `[-] EUR ${big.total} in cash is refused at the French till`, 409, 'PAYMENT_CASH_LIMIT_EXCEEDED');
  truthy('[-] ...naming the law and the amount', /L112-6/.test(refused.body) && /EUR 1000.00 or more/.test(refused.body), refused.body);
  expect(pay(fr, big, '999.99', 'CASH', 'EUR'), '[+] cash under the limit is taken', 201);
  expect(pay(fr, big, '0.01', 'CASH', 'EUR'), '[-] a penny more in cash for the same goods reaches the limit: the pieces are one payment', 409, 'PAYMENT_CASH_LIMIT_EXCEEDED');
  expect(pay(fr, big, String((Number(big.total) - 999.99).toFixed(2)), 'CARD', 'EUR'), '[+] the balance by card is taken', 201);
  const exact = sale(fr);
  expect(pay(fr, exact, '1000.00', 'CASH', 'EUR'), '[-] exactly the limit is refused: the law says or more', 409, 'PAYMENT_CASH_LIMIT_EXCEEDED');
  expect(pay(fr, exact, '1000.00', 'CASH', 'EUR', fr.tenant.owner.token), '[-] the owner fares no better', 409, 'PAYMENT_CASH_LIMIT_EXCEEDED');
  expect(pay(fr, exact, '5000.00', 'CASH', 'GBP'), '[abuse] cash in a currency the limit does not name is not caught by it: a rate is not a law', 201);

  // ── the German and Indian tills ──────────────────────────────────────────────────────────────────
  const deSale = sale(de);
  expect(pay(de, deSale, deSale.total, 'CASH', 'EUR'), `[+] a German till takes EUR ${deSale.total} in cash today`, 201);
  const inSale = sale(india);
  expect(pay(india, inSale, inSale.total, 'CASH', 'INR'), `[-] an Indian till refuses INR ${inSale.total} in cash`, 409, 'PAYMENT_CASH_LIMIT_EXCEEDED');
  expect(pay(india, inSale, '199999.00', 'CASH', 'INR'), '[+] ...and takes a rupee under two lakh', 201);
  const gbSale = sale(gb);
  expect(pay(gb, gbSale, gbSale.total, 'CASH', 'GBP'), '[+] a British till has no limit', 201);

  // ── abuse ────────────────────────────────────────────────────────────────────────────────────────
  expect(call('GET', `${OBL}?country=FR`, { token: gb.rival.owner.token }), '[abuse] a rival reads only the law, never a sheet with anything of ours in it', 200);
  // A payment is recorded in the caller's own business: a rival's owner tendering against our order
  // id reaches nothing of ours — no refusal is needed for our sale to stay unpaid and unconfirmed.
  const ours = sale(fr);
  const stray = pay(fr, ours, '1200.00', 'CASH', 'EUR', gb.tenant.owner.token);
  sleep(3);
  const after = data(call('GET', `${O}/${ours.id}`, { token: fr.cashier.token })) || {};
  truthy("[abuse] another business's owner tendering against our sale reaches nothing of ours: it stays unpaid", [403, 404, 422].includes(stray.status) || after.status === 'PENDING', { stray: stray.status, order: after.status });
  const inOurBooks = data(call('GET', `${PAY}/by-order/${ours.id}`, { token: fr.tenant.owner.token }));
  truthy('[abuse] ...and none of it is in our books', !(Array.isArray(inOurBooks) ? inOurBooks : []).some((p) => Number(p.amount) === 1200), inOurBooks);

  completed.add(1);
}
