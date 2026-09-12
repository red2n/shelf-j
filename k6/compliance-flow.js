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
  return { tenant, rival, storeA, storeB, variantId, cashierA };
}

export default function ({ tenant, rival, storeA, storeB, variantId, cashierA }) {
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
    expect(call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${storeA.id}&series=MAIN&period=${year}`, { token: cashierA.token }), 'a cashier does not audit', 403);
    expect(call('GET', `/api/order-svc/orders/${orderId}/fiscal-receipt`, { token: shopper.token }), "a shopper cannot read a till sale's receipt", [401, 403, 404]);
  });
}
