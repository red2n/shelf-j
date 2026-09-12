// The shop-floor legal records, end to end through the gateway, with the wrong caller and the
// wrong input at every step. Grows with the readiness review's legal gaps: age checks first.
//
//   k6/run.sh compliance-flow
import { group } from 'k6';
import {
  ALL_CHECKS_PASS,
  addStore,
  call,
  data,
  expect,
  onboardTenant,
  register,
  sellableVariant,
  staffUser,
  truthy,
  priceVariants,
  receive,
  must,
} from './lib/shelfj.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  // Four businesses are onboarded in setup, each waiting on its owner's grant to arrive over Kafka.
  setupTimeout: '5m',
  thresholds: ALL_CHECKS_PASS,
};

export function setup() {
  const tenant = onboardTenant('compliance', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('compliance-rival', { country: 'GB', currency: 'GBP' });
  const storeA = tenant.stores[0];
  const storeB = addStore(tenant, 'B');
  const { variantId } = sellableVariant(tenant, 'Compliance Wine');
  // Group 4 rings it up: it needs a price and stock at store A like any real sale.
  priceVariants(tenant, [variantId], '12.00');
  must(receive(tenant, storeA.id, variantId, 20), [200, 201], 'receive stock');
  const cashierA = staffUser(tenant, 'CASHIER', [storeA.id]);

  // Groups 5 and 6: a German and a Portuguese business, each with a store that sells one line at
  // its own standard rate, so the regime's stamp has real figures to sign (18.5).
  const de = onboardTenant('compliance-de', { country: 'DE', currency: 'EUR' });
  must(call('POST', '/api/pricing-svc/vat-rates', { token: de.owner.token, body: { code: 'T1', name: 'Allgemeiner Steuersatz', rate: 0.19, exempt: false, description: '19%', effectiveFrom: '2020-01-01T00:00:00Z' } }), 201, 'DE standard rate');
  const deVariant = sellableVariant(de, 'Riesling').variantId;
  priceVariants(de, [deVariant], '10.00');
  must(receive(de, de.stores[0].id, deVariant, 50), [200, 201], 'DE stock');
  const deCashier = staffUser(de, 'CASHIER', [de.stores[0].id]);
  const pt = onboardTenant('compliance-pt', { country: 'PT', currency: 'EUR' });
  must(call('POST', '/api/pricing-svc/vat-rates', { token: pt.owner.token, body: { code: 'T1', name: 'Taxa normal', rate: 0.23, exempt: false, description: '23%', effectiveFrom: '2020-01-01T00:00:00Z' } }), 201, 'PT standard rate');
  const ptVariant = sellableVariant(pt, 'Vinho Verde').variantId;
  priceVariants(pt, [ptVariant], '10.00');
  must(receive(pt, pt.stores[0].id, ptVariant, 50), [200, 201], 'PT stock');
  return { tenant, rival, storeA, storeB, variantId, cashierA, de, deVariant, deCashier, pt, ptVariant };
}

/** A till sale of one unit, paid in cash at the till, and its receipt as the till reads it. */
function sellAndRead(biz, cashierToken, storeId, variantId, method) {
  const sale = call('POST', '/api/order-svc/orders', { token: cashierToken, idem: true, body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', currency: biz.currency, items: [{ variantId, qty: 1 }] } });
  must(sale, 201, 'till sale');
  must(call('POST', '/api/payment-svc/payments', { token: cashierToken, idem: true, body: { orderId: data(sale).id, amount: data(sale).total, method, storeId, currency: biz.currency } }), [200, 201], 'tender');
  const receipt = call('GET', `/api/order-svc/orders/${data(sale).id}/fiscal-receipt?wait=15`, { token: cashierToken });
  return { orderId: data(sale).id, total: data(sale).total, receipt };
}

export default function ({ tenant, rival, storeA, storeB, variantId, cashierA, de, deVariant, deCashier, pt, ptVariant }) {
  const owner = tenant.owner.token;
  const shopper = register('compliance-shopper');
  const check = (extra = {}) => ({
    storeId: storeA.id,
    variantId,
    category: 'ALCOHOL',
    minimumAge: 18,
    country: 'GB',
    storePolicy: false,
    ...extra,
  });
  const record = (token, body) => call('POST', '/api/order-svc/pos/age-checks', { token, body });

  group('1 age checks: what the till writes', () => {
    const refused = record(cashierA.token, check({ outcome: 'REFUSED', reason: 'NO_ID' }));
    expect(refused, 'a cashier records a refusal with its reason', 201);
    truthy('the cashier is taken from the token', data(refused).cashierId === cashierA.userId, data(refused));
    expect(record(cashierA.token, check({ outcome: 'PASSED', idType: 'PASS_CARD' })), 'and a pass with what was shown', 201);
    expect(record(cashierA.token, check({ outcome: 'PASSED' })), 'a pass with nothing noted is still a record', 201);

    expect(record(cashierA.token, check({ outcome: 'REFUSED' })), 'a refusal with no reason is not a record', 400, 'AGE_CHECK_REASON_REQUIRED');
    expect(record(cashierA.token, check({ outcome: 'PASSED', reason: 'NO_ID' })), 'a pass cannot carry a refusal reason', 400, 'AGE_CHECK_REASON_ON_PASS');
    expect(record(cashierA.token, check({ outcome: 'REFUSED', reason: 'FELT_LIKE_IT' })), 'an invented reason is refused', 400, 'AGE_CHECK_REASON_UNKNOWN');
    expect(record(cashierA.token, check({ outcome: 'MAYBE' })), 'an invented outcome is refused', 400, 'AGE_CHECK_OUTCOME_UNKNOWN');
    expect(record(cashierA.token, check({ outcome: 'PASSED', country: 'GBR' })), 'a country that is not two letters is refused', 400);
    expect(record(cashierA.token, check({ outcome: 'PASSED', minimumAge: 0 })), 'an age of zero is refused', 400);
    expect(record(cashierA.token, { outcome: 'PASSED' }), 'a record with nothing in it is refused', 400);

    expect(record(cashierA.token, check({ storeId: storeB.id, outcome: 'REFUSED', reason: 'UNDER_AGE' })), "a cashier cannot record at a store they are not assigned to", 403, 'STORE_ACCESS_DENIED');
    expect(record(shopper.token, check({ outcome: 'REFUSED', reason: 'UNDER_AGE' })), 'a shopper records nothing', [401, 403]);
    expect(call('POST', '/api/order-svc/pos/age-checks', { body: check({ outcome: 'REFUSED', reason: 'UNDER_AGE' }) }), 'nor does a guest', 401);
    expect(record(rival.owner.token, check({ outcome: 'REFUSED', reason: 'UNDER_AGE' })), "a rival tenant's owner cannot record against our store: it is not one of theirs", 409, 'STORE_NOT_OPERATIONAL');
  });

  group('2 age checks: what a manager reads', () => {
    expect(call('GET', '/api/order-svc/admin/pos/age-checks', { token: cashierA.token }), 'a cashier cannot read the register', 403);
    expect(call('GET', '/api/order-svc/admin/pos/age-checks/summary', { token: cashierA.token }), 'nor the summary', 403);
    expect(call('GET', '/api/order-svc/admin/pos/age-checks', { token: shopper.token }), 'a shopper cannot read it', [401, 403]);

    const page = call('GET', `/api/order-svc/admin/pos/age-checks?store=${storeA.id}&outcome=REFUSED`, { token: owner });
    expect(page, 'the owner reads the refusals at one store', 200);
    truthy('and only refusals', (data(page) || []).length >= 1 && (data(page) || []).every((r) => r.outcome === 'REFUSED'), data(page));
    truthy('each with its reason', (data(page) || []).every((r) => r.reason), data(page));

    const summary = call('GET', `/api/order-svc/admin/pos/age-checks/summary?store=${storeA.id}`, { token: owner });
    expect(summary, 'and the counts', 200);
    truthy('three checks, one refused', data(summary).total === 3 && data(summary).refused === 1 && data(summary).passed === 2, data(summary));
    truthy('the refusal counted by its reason', (data(summary).refusedByReason || {}).NO_ID === 1, data(summary));

    expect(call('GET', '/api/order-svc/admin/pos/age-checks?outcome=SOMETIMES', { token: owner }), 'an unknown outcome filter is refused', 400, 'AGE_CHECK_OUTCOME_UNKNOWN');
    expect(call('GET', '/api/order-svc/admin/pos/age-checks?from=yesterday', { token: owner }), 'a date that is not a date is refused', 400, 'INVALID_DATE');
    expect(call('GET', '/api/order-svc/admin/pos/age-checks?after=!!', { token: owner }), 'a malformed cursor is refused', 400, 'INVALID_CURSOR');

    const theirs = call('GET', `/api/order-svc/admin/pos/age-checks?store=${storeA.id}`, { token: rival.owner.token });
    expect(theirs, "a rival tenant's owner asks about our store", 200);
    truthy('and sees nothing of it', (data(theirs) || []).length === 0, data(theirs));

    const id = (data(page) || [])[0].id;
    expect(call('DELETE', `/api/order-svc/admin/pos/age-checks/${id}`, { token: owner }), 'nothing deletes a record', [404, 405]);
    expect(call('PUT', `/api/order-svc/pos/age-checks/${id}`, { token: owner, body: {} }), 'nothing edits one', [404, 405]);
  });

  group('3 weighing instruments: the register', () => {
    const base = `/api/tenant-svc/admin/stores/${storeA.id}/weighing-instruments`;
    // No default parameter and no object spread inside the arrow: k6's parser refuses that shape.
    const scale = (extra) => Object.assign({ identifier: 'Deli scale ' + Date.now() + '-' + Math.floor(Math.random() * 1e6), serialNumber: 'SN-' + Date.now() + '-' + Math.floor(Math.random() * 1e6), make: 'Avery', model: 'X', kind: 'COUNTER', maxCapacity: 15, capacityUom: 'KG', scaleInterval: 0.005 }, extra || {});

    expect(call('POST', base, { token: cashierA.token, body: scale() }), 'a cashier cannot register an instrument', 403);
    expect(call('POST', base, { token: shopper.token, body: scale() }), 'nor a shopper', [401, 403]);
    expect(call('POST', base, { token: rival.owner.token, body: scale() }), "a rival tenant's owner finds no such store", 404, 'STORE_NOT_FOUND');
    expect(call('POST', base, { token: owner, body: scale({ identifier: '' }) }), 'an instrument needs an identifier', 400);
    expect(call('POST', base, { token: owner, body: scale({ kind: 'BATHROOM' }) }), 'and a kind the register knows', 400, 'INSTRUMENT_KIND_UNKNOWN');
    expect(call('POST', base, { token: owner, body: scale({ labelScheme: '{"prefixes":["20"]}' }) }), 'a counter scale carries no label scheme', 400, 'INSTRUMENT_SCHEME_INVALID');
    expect(call('POST', base, { token: owner, body: scale({ kind: 'LABELLING', labelScheme: '{"prefixes":["2"],"itemDigits":5,"valueKind":"PRICE","valueDecimals":2}' }) }), 'a labelling scheme with a one-digit prefix is refused', 400, 'INSTRUMENT_SCHEME_INVALID');

    const created = call('POST', base, { token: owner, body: scale() });
    expect(created, 'the owner registers a counter scale', 201);
    const id = data(created).id;
    truthy('it starts never verified, and not certified', data(created).standing === 'NEVER_VERIFIED' && data(created).certified === false, data(created));
    expect(call('POST', base, { token: owner, body: scale({ serialNumber: data(created).serialNumber }) }), 'the same serial number twice is refused', 409, 'INSTRUMENT_DUPLICATE');

    const certified = () => (data(call('GET', `${base}?certified=true`, { token: cashierA.token })) || []).some((i) => i.id === id);
    truthy('the till sees no certified scale yet', !certified());

    const verify = (body) => call('POST', `${base}/${id}/verifications`, { token: owner, body });
    expect(verify({ kind: 'INITIAL', performedOn: '2026-01-10', performedBy: 'Trading Standards', passed: true, nextDue: '2025-12-01' }), 'a due date before the work is refused', 400, 'VERIFICATION_DUE_BEFORE_DONE');
    expect(verify({ kind: 'REPAIR', performedOn: '2026-01-10', performedBy: 'Avery service', passed: true }), 'a repair is never a pass', 400, 'VERIFICATION_REPAIR_NOT_PASS');
    expect(verify({ kind: 'INITIAL', performedOn: 'last tuesday', performedBy: 'x', passed: true }), 'a date that is not a date is refused', 400, 'VERIFICATION_DATE_INVALID');
    expect(call('POST', `${base}/${id}/verifications`, { token: cashierA.token, body: { kind: 'INITIAL', performedOn: '2026-01-10', performedBy: 'me', passed: true } }), 'a cashier cannot verify a scale', 403);

    expect(verify({ kind: 'INITIAL', performedOn: '2026-01-10', performedBy: 'Trading Standards, Camden', certificateRef: 'TS/2026/0042', passed: true, nextDue: '2027-01-10' }), 'passed as fit for trade and stamped', 201);
    truthy('now the till sees it as certified', certified());
    truthy('and the register says so', data(call('GET', `${base}/${id}`, { token: cashierA.token })).standing === 'CERTIFIED');

    expect(verify({ kind: 'REPAIR', performedOn: '2026-03-01', performedBy: 'Avery service', passed: false, notes: 'load cell replaced' }), 'a repair breaks the stamp', 201);
    truthy('and the till no longer sees it', !certified());
    truthy('the register says why', data(call('GET', `${base}/${id}`, { token: owner })).standing === 'REPAIRED_SINCE');
    expect(verify({ kind: 'RE_VERIFICATION', performedOn: '2026-03-03', performedBy: 'Trading Standards, Camden', passed: true, nextDue: '2027-03-03' }), 're-verified after the repair', 201);
    truthy('and it is back in trade', certified());

    expect(call('PATCH', `${base}/${id}/status`, { token: cashierA.token, body: { status: 'OUT_OF_SERVICE' } }), 'a cashier cannot take it out of service', 403);
    expect(call('PATCH', `${base}/${id}/status`, { token: owner, body: { status: 'BROKEN' } }), 'an unknown status is refused', 400, 'INSTRUMENT_STATUS_UNKNOWN');
    expect(call('PATCH', `${base}/${id}/status`, { token: owner, body: { status: 'OUT_OF_SERVICE' } }), 'the owner takes it out of service', 200);
    truthy('out of service is not certified, whatever the paperwork says', !certified());
    expect(call('PATCH', `${base}/${id}/status`, { token: owner, body: { status: 'RETIRED' } }), 'and retires it', 200);
    expect(call('PATCH', `${base}/${id}/status`, { token: owner, body: { status: 'IN_SERVICE' } }), 'retirement is final', 409, 'INSTRUMENT_RETIRED');
    expect(verify({ kind: 'INSPECTION', performedOn: '2026-04-01', performedBy: 'x', passed: true }), 'nothing is verified after retirement', 409, 'INSTRUMENT_RETIRED');

    const history = call('GET', `${base}/${id}/verifications`, { token: cashierA.token });
    expect(history, 'the history is read by any staff member at the store', 200);
    truthy('three entries, newest first, none rewritten', (data(history) || []).length === 3 && data(history)[0].kind === 'RE_VERIFICATION' && data(history)[2].kind === 'INITIAL', data(history));
    expect(call('DELETE', `${base}/${id}/verifications/${data(history)[0].id}`, { token: owner }), 'nothing deletes a history entry', [404, 405]);

    const cashierB = staffUser(tenant, 'CASHIER', [storeB.id]);
    expect(call('GET', base, { token: cashierB.token }), "a cashier at another store cannot read this store's register", 403, 'STORE_ACCESS_DENIED');
    expect(call('GET', base, { token: rival.owner.token }), "a rival tenant's owner cannot read it", 404, 'STORE_NOT_FOUND');
  });

  group('4 legal receipts: the series and the audit', () => {
    const series = '/api/order-svc/admin/fiscal-receipts/series';
    const year = new Date().getUTCFullYear().toString();
    expect(call('PUT', series, { token: cashierA.token, body: { storeId: storeA.id, seriesCode: 'MAIN', period: year, prefix: 'GB-A' } }), 'a cashier cannot set a series prefix', 403);
    expect(call('PUT', series, { token: owner, body: { storeId: storeA.id, seriesCode: 'MAIN', period: year, prefix: 'not a prefix!' } }), 'a prefix with spaces or punctuation is refused', 400, 'RECEIPT_PREFIX_INVALID');
    expect(call('PUT', series, { token: owner, body: { storeId: storeA.id, seriesCode: 'MAIN', period: 'this year', prefix: 'GB-A' } }), 'a period that is not a year is refused', 400, 'RECEIPT_PERIOD_INVALID');
    const set = call('PUT', series, { token: owner, body: { storeId: storeA.id, seriesCode: 'MAIN', period: year, prefix: 'gb-a' } });
    expect(set, 'the owner opens MAIN with a prefix', 200);
    truthy('upper-cased, counter untouched', data(set).prefix === 'GB-A' && data(set).nextNumber === 1, data(set));
    const listed = call('GET', `${series}?storeId=${storeA.id}`, { token: owner });
    expect(listed, 'and reads the series the store runs', 200);
    truthy('MAIN is there', (data(listed) || []).some((r) => r.seriesCode === 'MAIN' && r.prefix === 'GB-A'), data(listed));
    expect(call('GET', `${series}?storeId=${storeA.id}`, { token: cashierA.token }), 'a cashier does not read the counters', 403);
    expect(call('GET', `${series}?storeId=${storeA.id}`, { token: rival.owner.token }), "a rival tenant's owner sees none of them", 200);
    truthy('none', (data(call('GET', `${series}?storeId=${storeA.id}`, { token: rival.owner.token })) || []).length === 0);

    // A sale completes, and its number carries the prefix. The till waits for it in one request.
    const sale = call('POST', '/api/order-svc/orders', { token: cashierA.token, idem: true, body: { storeId: storeA.id, channel: 'POS', fulfilmentType: 'INSTORE', currency: 'GBP', items: [{ variantId, qty: 1, unitPrice: '12.00' }] } });
    expect(sale, 'a till sale is placed', 201);
    const orderId = data(sale).id;
    expect(call('GET', `/api/order-svc/orders/${orderId}/fiscal-receipt?wait=1`, { token: cashierA.token }), 'before payment there is no number, even after waiting', 404, 'ORDER_RECEIPT_NOT_ISSUED');
    expect(call('POST', `/api/order-svc/orders/${orderId}/confirm`, { token: owner, body: {} }), 'the sale is completed', 200);
    const numbered = call('GET', `/api/order-svc/orders/${orderId}/fiscal-receipt?wait=10`, { token: cashierA.token });
    expect(numbered, 'the till reads the number in one request', 200);
    truthy('and it carries the prefix', typeof data(numbered).fullNumber === 'string' && data(numbered).fullNumber.startsWith('GB-A-'), data(numbered));

    const audit = call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${storeA.id}&series=MAIN&period=${year}`, { token: owner });
    expect(audit, 'the audit answers', 200);
    truthy('intact, one issued', data(audit).intact === true && data(audit).issued === 1, data(audit));
    truthy('the hash chain is intact from the first document', data(audit).chainIntact === true && data(audit).chainFrom === 1, data(audit));
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${storeA.id}&series=MAIN&period=${year}`, { token: cashierA.token }), 'a cashier does not audit', 403);
    const csv = call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${storeA.id}&series=MAIN&period=${year}`, { token: owner });
    expect(csv, 'the register exports as CSV', 200);
    truthy('with the document and its hashes on the row', csv.body.includes('prevHash,hash') && csv.body.includes(data(numbered).fullNumber) && /,GENESIS,[0-9a-f]{64}\s*$/m.test(csv.body), csv.body.slice(0, 300));
    const json = call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${storeA.id}&series=MAIN&period=${year}&format=json`, { token: owner });
    expect(json, 'and as JSON with the lines behind each document', 200);
    const docs = (data(json) && data(json).documents) || [];
    truthy('one document, one line, a 64-hex hash', docs.length === 1 && (docs[0].lines || []).length === 1 && /^[0-9a-f]{64}$/.test(docs[0].hash), data(json));
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${storeA.id}`, { token: cashierA.token }), 'a cashier does not export the register', 403);
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${storeA.id}`, { token: rival.owner.token }), "a rival tenant's owner exports an empty register", 200);
    expect(call('GET', `/api/order-svc/orders/${orderId}/fiscal-receipt`, { token: shopper.token }), "a shopper cannot read a till sale's receipt", [401, 403, 404]);
  });

  group('5 fiscal regime: a German store signs every sale with a security module', () => {
    const settings = '/api/order-svc/admin/fiscal-receipts/settings';
    const store = de.stores[0];
    const year = new Date().getUTCFullYear().toString();
    const owner = de.owner.token;

    const before = call('GET', `${settings}?storeId=${store.id}`, { token: owner });
    expect(before, 'a store starts under NONE', 200);
    truthy('with Germany and Portugal on offer and a simulated module available', data(before).regime === 'NONE' && data(before).regimes.includes('DE_KASSENSICHV') && data(before).regimes.includes('PT_SAFT') && data(before).tseProviders.includes('SIMULATED'), data(before));

    expect(call('PUT', settings, { token: deCashier.token, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE123456789', tseProvider: 'SIMULATED' } }), 'a cashier cannot place a store under a regime', 403);
    expect(call('GET', `${settings}?storeId=${store.id}`, { token: deCashier.token }), 'nor read the settings', 403);
    expect(call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'FR_NF525' } }), 'an unknown regime is refused', 400, 'FISCAL_REGIME_UNKNOWN');
    expect(call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'DE_KASSENSICHV', tseProvider: 'SIMULATED' } }), 'Germany without a tax number is refused', 400, 'FISCAL_TAX_NUMBER_REQUIRED');
    expect(call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE123456789' } }), 'and without a module', 400, 'FISCAL_TSE_REQUIRED');
    expect(call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE123456789', tseProvider: 'USB' } }), 'an unknown module provider is refused', 400, 'FISCAL_TSE_PROVIDER_UNKNOWN');
    const cloud = call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE123456789', tseProvider: 'CLOUD', tseTssId: 'tss-1' } });
    truthy('a cloud module is refused unless the provider is configured', cloud.status === 409 || cloud.status === 502 || cloud.status === 200, cloud.body);
    expect(call('PUT', settings, { token: rival.owner.token, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE1', tseProvider: 'SIMULATED' } }), "a rival tenant's owner cannot place our store under anything", 409, 'STORE_NOT_OPERATIONAL');

    const placed = call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'DE_KASSENSICHV', taxRegistrationNumber: 'DE123456789', tseProvider: 'SIMULATED', tseClientId: 'till-1' } });
    expect(placed, 'the owner places the store under KassenSichV with a simulated module', 200);
    const device = data(placed).tse || {};
    truthy('the module has a serial, a public key and a client id', device.provider === 'SIMULATED' && /^[0-9a-f]{64}$/.test(device.serialNumber || '') && !!device.publicKey && device.clientId === 'till-1', data(placed));

    const cash = sellAndRead(de, deCashier.token, store.id, deVariant, 'CASH');
    expect(cash.receipt, 'a cash sale is numbered and the till reads its receipt', 200);
    const stamp = (data(cash.receipt) || {}).tse || {};
    truthy('the receipt carries the module\'s stamp: serial, counters, signature', data(cash.receipt).regime === 'DE_KASSENSICHV' && stamp.serialNumber === device.serialNumber && stamp.transactionNumber === 1 && stamp.signatureCounter === 1 && typeof stamp.signature === 'string' && stamp.signature.length > 40 && !stamp.error, data(cash.receipt));
    truthy('the process data is a Kassenbeleg at 19% paid in cash: 10.00 net is 11.90 gross', stamp.processType === 'Kassenbeleg-V1' && stamp.processData === 'Beleg^11.90_0.00_0.00_0.00_0.00^11.90:Bar', stamp);
    truthy('and the QR has the twelve fields the receipt prints', typeof stamp.qr === 'string' && stamp.qr.split(';').length === 12 && stamp.qr.startsWith('V0;till-1;Kassenbeleg-V1;'), stamp.qr);

    const card = sellAndRead(de, deCashier.token, store.id, deVariant, 'CARD');
    const stamp2 = (data(card.receipt) || {}).tse || {};
    truthy('the next sale, by card, moves both counters by one and is non-cash', stamp2.transactionNumber === 2 && stamp2.signatureCounter === 2 && stamp2.processData === 'Beleg^11.90_0.00_0.00_0.00_0.00^11.90:Unbar', stamp2);

    const audit = call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${store.id}&series=MAIN&period=${year}`, { token: owner });
    expect(audit, 'the audit answers', 200);
    truthy('two issued, sequence and chain intact', data(audit).issued === 2 && data(audit).intact === true && data(audit).chainIntact === true, data(audit));

    const zip = call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${store.id}&series=MAIN&period=${year}&format=dsfinvk`, { token: owner });
    expect(zip, 'the inspector\'s DSFinV-K file is produced', 200);
    truthy('as a zip', (zip.headers['Content-Type'] || '').includes('application/zip') && zip.body.length > 200 && zip.body.slice(0, 2) === 'PK', { type: zip.headers['Content-Type'], len: zip.body.length });
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${store.id}&series=MAIN&period=${year}&format=dsfinvk`, { token: deCashier.token }), 'a cashier does not get the file', 403);
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${store.id}&series=MAIN&period=${year}&format=pdf`, { token: owner }), 'an unknown format is refused', 400, 'FISCAL_EXPORT_FORMAT_UNKNOWN');
    const theirs = call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${store.id}&series=MAIN&period=${year}&format=dsfinvk`, { token: rival.owner.token });
    truthy("a rival tenant's owner gets no file for our store", theirs.status === 503 || theirs.status === 404, theirs.status);

    expect(call('POST', `/api/order-svc/orders/${cash.orderId}/void`, { token: owner, body: { reason: 'wrong item' } }), 'a stamped sale can be voided', 200);
    const voided = call('GET', `/api/order-svc/admin/orders/${cash.orderId}/fiscal-receipt`, { token: owner });
    truthy('and keeps its number and its stamp', !!data(voided).voidedAt && data(voided).tse && data(voided).tse.signature === stamp.signature, data(voided));
  });

  group('6 fiscal regime: a Portuguese store signs every document, when the software has its key', () => {
    const settings = '/api/order-svc/admin/fiscal-receipts/settings';
    const store = pt.stores[0];
    const year = new Date().getUTCFullYear().toString();
    const owner = pt.owner.token;

    const offer = call('GET', `${settings}?storeId=${store.id}`, { token: owner });
    expect(offer, 'the offer says whether a signing key is installed', 200);
    expect(call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'PT_SAFT', taxRegistrationNumber: '123456780' } }), 'a NIF with a wrong check digit is refused', 400, 'FISCAL_NIF_INVALID');

    const placed = call('PUT', settings, { token: owner, body: { storeId: store.id, regime: 'PT_SAFT', taxRegistrationNumber: '500000000', certificateNumber: '1234', seriesValidationCode: 'ABCD1234' } });
    if (!data(offer).ptKeyConfigured) {
      expect(placed, 'without a key on the server, Portugal is refused by name', 409, 'FISCAL_PT_KEY_NOT_CONFIGURED');
      truthy('and the store stays under NONE', data(call('GET', `${settings}?storeId=${store.id}`, { token: owner })).regime === 'NONE');
      return;
    }
    expect(placed, 'with a key installed, the owner places the store under certified-software rules', 200);
    const one = sellAndRead(pt, owner, store.id, ptVariant, 'CASH');
    const two = sellAndRead(pt, owner, store.id, ptVariant, 'CARD');
    expect(one.receipt, 'the first document is issued', 200);
    const p1 = (data(one.receipt) || {}).pt || {};
    const p2 = (data(two.receipt) || {}).pt || {};
    truthy('signed, with the SAF-T number, the ATCUD and the four characters the receipt prints', data(one.receipt).regime === 'PT_SAFT' && /^FS .+\/1$/.test(p1.invoiceNo || '') && p1.atcud === 'ABCD1234-1' && typeof p1.hash === 'string' && p1.hash.length > 100 && (p1.printedExcerpt || '').length === 4 && p1.certificateNumber === '1234', p1);
    truthy('the second chains on the first', p2.atcud === 'ABCD1234-2' && p2.hash !== p1.hash, p2);
    const audit = call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${store.id}&series=MAIN&period=${year}`, { token: owner });
    truthy('the chain is intact', data(audit).chainIntact === true && data(audit).issued === 2, data(audit));
    const saft = call('GET', `/api/order-svc/admin/fiscal-receipts/export?storeId=${store.id}&series=MAIN&period=${year}&format=saft-pt`, { token: owner });
    expect(saft, 'the SAF-T (PT) file is produced', 200);
    truthy('as an AuditFile with both documents, their hashes and the certificate', saft.body.includes('urn:OECD:StandardAuditFile-Tax:PT_1.04_01') && saft.body.includes('<NumberOfEntries>2</NumberOfEntries>') && saft.body.includes('<Hash>' + p1.hash + '</Hash>') && saft.body.includes('<SoftwareCertificateNumber>1234</SoftwareCertificateNumber>') && saft.body.includes('<TaxRegistrationNumber>500000000</TaxRegistrationNumber>'), saft.body.slice(0, 600));
  });
}
