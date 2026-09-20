// Deposit return schemes (09.16), through the gateway: the register lists the schemes that reach
// a country with their status; a German business's till puts the Pfand on each container the
// scheme takes back as its own line beside the drink, taxed as the drink; a British till charges
// none today with the UK scheme shown as upcoming from 1 October 2027, outside the scope of VAT;
// empties brought back are paid out at the scheme's amount, once per till key, and the drawer
// carries the pay-out; the period's report shows charged, refunded and unredeemed by material.
// Refused: a container the scheme does not take back, no lines, too many of one kind, a refund
// where no scheme is in force, a refund without a key, a container with a material and no volume,
// a material the schemes do not know, a volume no drink comes in; abuse: a rival business's owner
// at our till, a rival reading our refund, a cashier reading the report, a period ending before it
// starts.
//
//   k6/run.sh deposit-return-flow
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
const CONFIG = '/api/tenant-svc/storefront/config';
const P = '/api/product-svc';
const O = '/api/order-svc/orders';
const REFUNDS = `${O}/container-refunds`;
const REPORT = '/api/order-svc/admin/reports/deposits';
const PAY = '/api/payment-svc/payments';
const MOVEMENTS = '/api/payment-svc/admin/cash/movements';

export function setup() {
  const de = sellingTenant('deposit-de', { country: 'DE', currency: 'EUR', price: '1.50' });
  const gb = sellingTenant('deposit-gb', { country: 'GB', currency: 'GBP', price: '1.50' });
  return { de, gb };
}

const schemes = (res) => ((data(res) || {}).depositSchemes || []);
const scheme = (res, scope) => schemes(res).find((s) => s.scope === scope) || {};
const day = (offset) => new Date(Date.now() + offset * 86400 * 1000).toISOString();

export default function ({ de, gb }) {
  const compliance = (t, body, token = t.tenant.owner.token) =>
    call('PUT', `${P}/admin/products/variants/${t.variantId}/compliance`, { token, body });
  const sale = (t, qty) =>
    call('POST', O, { token: t.cashier.token, idem: true, body: { storeId: t.store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: t.variantId, qty }] } });
  const refund = (t, body, { token = t.cashier.token, idem = true, key } = {}) =>
    call('POST', REFUNDS, { token, idem: key || idem, body: { storeId: t.store.id, tillSessionId: t.session, ...body } });

  // ── the register ─────────────────────────────────────────────────────────────────────────────────
  const german = call('GET', OBL, { token: de.tenant.owner.token });
  expect(german, "[+] a German business's sheet carries the deposit scheme that reaches Germany", 200);
  truthy('[+] ...the Pfand in force: EUR 0.25 on PET, aluminium, steel and glass from 100 ml to 3 l, taxed as the drink', scheme(german, 'DE').status === 'IN_FORCE' && Number(scheme(german, 'DE').depositEach) === 0.25 && scheme(german, 'DE').currency === 'EUR' && scheme(german, 'DE').vatTreatment === 'STANDARD' && (scheme(german, 'DE').materials || []).length === 4 && scheme(german, 'DE').minVolumeMl === 100 && scheme(german, 'DE').maxVolumeMl === 3000 && /VerpackG|Verpackungsgesetz/.test(scheme(german, 'DE').citation), schemes(german));
  const british = call('GET', OBL, { token: gb.tenant.owner.token });
  truthy('[+] a British business has the UK scheme upcoming from 1 October 2027: 20p on PET, aluminium and steel, outside the scope of VAT', scheme(british, 'GB').status === 'UPCOMING' && scheme(british, 'GB').effectiveFrom === '2027-10-01' && Number(scheme(british, 'GB').depositEach) === 0.2 && scheme(british, 'GB').vatTreatment === 'OUTSIDE_SCOPE' && !(scheme(british, 'GB').materials || []).includes('GLASS') && /2025\/67|55B/.test(scheme(british, 'GB').citation), schemes(british));
  truthy('[+] ...asked about that day, in force', scheme(call('GET', `${OBL}?on=2027-10-01`, { token: gb.tenant.owner.token }), 'GB').status === 'IN_FORCE', 'GB on the day');
  const deConfig = call('GET', `${CONFIG}?store=${de.store.id}`, { storefront: de.tenant.tenantId });
  expect(deConfig, "[+] the German store's storefront config names the scheme in force there", 200);
  truthy('[+] ...with the deposit and the containers it takes back', (data(deConfig).depositScheme || {}).scope === 'DE' && Number(data(deConfig).depositScheme.depositEach) === 0.25, data(deConfig).depositScheme);
  truthy('[+] the British store has none today', !data(call('GET', `${CONFIG}?store=${gb.store.id}`, { storefront: gb.tenant.tenantId })).depositScheme, 'GB config');

  // ── the catalogue says what the drink comes in ───────────────────────────────────────────────────
  expect(compliance(de, { depositMaterial: 'PET' }), '[-] a material without a volume is refused', 400, 'PRODUCT_DEPOSIT_CONTAINER_INCOMPLETE');
  expect(compliance(de, { depositMaterial: 'CARDBOARD', depositVolumeMl: 500 }), '[-] a material the schemes do not know is refused', 400, 'PRODUCT_DEPOSIT_MATERIAL_UNKNOWN');
  expect(compliance(de, { depositMaterial: 'PET', depositVolumeMl: 20000 }), '[-] a volume no drink comes in is refused', 400, 'PRODUCT_DEPOSIT_VOLUME_OUT_OF_RANGE');
  expect(compliance(de, { depositMaterial: 'PET', depositVolumeMl: 500 }, de.cashier.token), '[-] a cashier does not set it', 403);
  const set = compliance(de, { depositMaterial: 'pet', depositVolumeMl: 500 });
  expect(set, '[+] the owner records a 500 ml PET bottle', 200);
  truthy('[+] ...kept upper case, with the volume', data(set).depositMaterial === 'PET' && data(set).depositVolumeMl === 500, data(set));
  expect(compliance(gb, { depositMaterial: 'ALUMINIUM', depositVolumeMl: 330 }), '[+] the British owner records a 330 ml can', 200);
  const resolved = call('GET', `${P}/admin/products/variants/resolve?ids=${de.variantId}`, { token: de.cashier.token });
  expect(resolved, '[+] resolving the variant carries the container too', 200);
  truthy('[+] ...PET, 500 ml', (data(resolved) || []).some((v) => v.variantId === de.variantId && v.depositMaterial === 'PET' && v.depositVolumeMl === 500), data(resolved));

  // ── the German till: the deposit as its own line ─────────────────────────────────────────────────
  const three = sale(de, 3);
  expect(three, '[+] a German till sells three bottles', 201);
  const o = data(three) || {};
  truthy('[+] ...one deposit line: 3 × EUR 0.25 = 0.75, taxed as the drink, the scheme named', (o.deposits || []).length === 1 && Number(o.deposits[0].amount) === 0.75 && o.deposits[0].material === 'PET' && o.deposits[0].volumeMl === 500 && Number(o.deposits[0].depositEach) === 0.25 && o.deposits[0].vatTreatment === 'STANDARD' && o.deposits[0].schemeScope === 'DE', o.deposits);
  truthy('[+] ...added to the total, never to the subtotal', Number(o.depositAmount) === 0.75 && Math.abs(Number(o.total) - (Number(o.subtotal) + Number(o.taxAmount) - Number(o.discountAmount) - Number(o.promotionDiscount || 0) + 0.75)) < 0.005, { subtotal: o.subtotal, tax: o.taxAmount, total: o.total });
  const paid = call('POST', PAY, { token: de.cashier.token, idem: true, body: { orderId: o.id, amount: o.total, method: 'CASH', storeId: de.store.id, currency: 'EUR' } });
  expect(paid, '[+] the customer pays the total with the deposit in it', 201);
  const again = call('GET', `${O}/${o.id}`, { token: de.cashier.token });
  truthy('[+] the order read back carries its deposit line', ((data(again) || {}).deposits || []).length === 1 && Number(data(again).depositAmount) === 0.75, data(again));
  const gbSale = sale(gb, 2);
  expect(gbSale, '[+] a British till sells two cans', 201);
  truthy('[+] ...with no deposit today: the scheme is upcoming', !Number((data(gbSale) || {}).depositAmount) && ((data(gbSale) || {}).deposits || []).length === 0, data(gbSale));

  // ── empties come back ────────────────────────────────────────────────────────────────────────────
  for (const t of [de, gb]) {
    t.session = must(call('POST', '/api/iam-svc/auth/pos/sessions', { token: t.cashier.token, body: { storeId: t.store.id } }), 201, 'till session').id;
  }
  expect(refund(de, { lines: [] }), '[-] a refund needs containers', 400, 'ORDER_CONTAINER_LINES_INVALID');
  expect(refund(de, { lines: [{ material: 'GLASS', volumeMl: 5000, count: 1 }] }), '[-] a five-litre glass jar is not a container the scheme takes back', 400, 'ORDER_CONTAINER_NOT_IN_SCHEME');
  expect(refund(de, { lines: [{ material: 'PET', volumeMl: 500, count: 501 }] }), '[-] more than five hundred of one kind is not one refund', 400, 'ORDER_CONTAINER_COUNT_TOO_MANY');
  expect(refund(de, { lines: [{ material: 'PET', volumeMl: 500, count: 1 }] }, { idem: false }), '[-] a refund without a till key is refused', 400, 'MISSING_IDEMPOTENCY_KEY');
  expect(refund(gb, { lines: [{ material: 'ALUMINIUM', volumeMl: 330, count: 1 }] }), '[-] the British till pays nothing back: no scheme is in force there yet', 409, 'ORDER_DEPOSIT_SCHEME_NOT_IN_FORCE');
  const key = `empties-${de.session}`;
  const back = refund(de, { lines: [{ material: 'pet', volumeMl: 500, count: 2 }, { material: 'ALUMINIUM', volumeMl: 330, count: 1 }] }, { key });
  expect(back, '[+] two bottles and a can come back', 201);
  const r = data(back) || {};
  truthy('[+] ...EUR 0.75 paid back on three containers, each kind on its own line', Number(r.amount) === 0.75 && r.containers === 3 && (r.lines || []).length === 2 && r.lines.every((l) => Number(l.depositEach) === 0.25) && r.schemeScope === 'DE' && r.tillSessionId === de.session, r);
  const replay = refund(de, { lines: [{ material: 'PET', volumeMl: 500, count: 2 }, { material: 'ALUMINIUM', volumeMl: 330, count: 1 }] }, { key });
  expect(replay, '[+] the till retrying with the same key pays out once', 201);
  truthy('[+] ...the same refund', (data(replay) || {}).id === r.id, data(replay));
  expect(call('GET', `${REFUNDS}/${r.id}`, { token: de.cashier.token }), '[+] the refund is read back', 200);
  let movement = null;
  for (let i = 0; i < 20 && !movement; i++) {
    const m = data(call('GET', `${MOVEMENTS}?tillSessionId=${de.session}`, { token: de.tenant.owner.token }));
    movement = (Array.isArray(m) ? m : (m || {}).items || []).find((x) => x.direction === 'PAY_OUT' && Number(x.amount) === 0.75) || null;
    if (!movement) sleep(0.5); // the event travels through Kafka
  }
  truthy('[+] the drawer carries the pay-out, once, against the till session', movement !== null, 'no PAY_OUT of 0.75 for the session');

  // ── the period's report ──────────────────────────────────────────────────────────────────────────
  const report = call('GET', `${REPORT}?from=${day(-1)}&to=${day(1)}`, { token: de.tenant.owner.token });
  expect(report, "[+] the owner reads the period's deposits", 200);
  const rep = data(report) || {};
  truthy('[+] ...three charged, three refunded, nothing unredeemed, PET and aluminium by material', rep.chargedContainers === 3 && rep.refundedContainers === 3 && Number(rep.chargedAmount) === 0.75 && Number(rep.refundedAmount) === 0.75 && Number(rep.unredeemedAmount) === 0 && (rep.byMaterial || []).some((m) => m.material === 'PET' && m.chargedContainers === 3 && m.refundedContainers === 2) && rep.byMaterial.some((m) => m.material === 'ALUMINIUM' && m.refundedContainers === 1), rep);
  truthy('[+] ...in euros', rep.currency === 'EUR', rep.currency);
  expect(call('GET', `${REPORT}?from=${day(1)}&to=${day(-1)}`, { token: de.tenant.owner.token }), '[-] a period ending before it starts', 400, 'ORDER_REPORT_PERIOD_INVALID');
  expect(call('GET', `${REPORT}?from=${day(-1)}&to=${day(1)}`, { token: de.cashier.token }), '[abuse] a cashier does not read the report', 403);
  truthy("[abuse] the British business's report shows none of it", Number((data(call('GET', `${REPORT}?from=${day(-1)}&to=${day(1)}`, { token: gb.tenant.owner.token })) || {}).chargedContainers) === 0, 'GB report');

  // ── abuse ────────────────────────────────────────────────────────────────────────────────────────
  expect(refund(de, { lines: [{ material: 'PET', volumeMl: 500, count: 1 }] }, { token: de.rival.owner.token }), "[abuse] another business's owner pays nothing out at our till", [403, 404, 409]);
  expect(call('GET', `${REFUNDS}/${r.id}`, { token: de.rival.owner.token }), '[abuse] another business does not see our refund', 404, 'ORDER_CONTAINER_REFUND_NOT_FOUND');
  expect(call('GET', `${REFUNDS}/${r.id}`, {}), '[abuse] nobody reads a refund without signing in', 401);
  expect(call('POST', REFUNDS, { token: de.cashier.token, idem: true, body: { storeId: gb.store.id, tillSessionId: de.session, lines: [{ material: 'PET', volumeMl: 500, count: 1 }] } }), "[abuse] another business's store is not ours to pay out at", [403, 404, 409]);

  completed.add(1);
}
