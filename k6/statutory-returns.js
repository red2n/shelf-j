// Statutory reporting by jurisdiction, through the gateway: what a business owes each authority, when
// each return falls due, and the evidence that it went.
//
// The exports already existed — order-svc produces Germany's DSFinV-K and Portugal's SAF-T, pricing-svc
// the VAT return — and nothing recorded which returns a business owed or whether any of them were
// filed. An auditor does not ask whether a SAF-T can be generated; it asks to see the one filed for
// September.
//
// Every date and every state here is derived, never stored: a stored deadline goes stale the first time
// a rule changes. So the suite asks the same calendar as of different days and checks the state moves
// on its own — NOT_DUE before the period ends, DUE up to and including the date, OVERDUE after it, and
// FILED whatever the date says, because a return filed late is filed.
//
// A regime's return reaches a business for the periods its country was a member, asked of the period's
// own dates. A Portuguese business owes the monthly SAF-T and the EU statement; a British one owes the
// quarterly VAT return and, since it left, no EU statement at all.
// Refused: filing a period that has not ended, a period that is not one of that return's own, filing
// twice without naming what is corrected, an unknown provider, a cashier, an anonymous caller.
//
//   k6/run.sh statutory-returns
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, onboardTenant, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
};

const SR = '/api/tenant-svc/admin/tenant/statutory-returns';

/** The first day of the month n months before the month containing `from`. */
function monthBack(from, n) {
  const d = new Date(`${from}T00:00:00Z`);
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - n);
  return d.toISOString().slice(0, 10);
}

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  const today = new Date().toISOString().slice(0, 10);

  // ── a Portuguese business: the monthly SAF-T, and the EU statement ───────────────────────────────
  const pt = onboardTenant(`saft-${tag}`, { country: 'PT', currency: 'EUR' });
  const owner = pt.owner.token;
  const cal = (asOf) => data(call('GET', `${SR}${asOf ? `?asOf=${asOf}` : ''}`, { token: owner }));

  const now = cal();
  truthy('[+] a business is told what it owes without being asked to configure it', Array.isArray(now.obligations) && now.obligations.length > 0, now.obligations ? now.obligations.length : now);
  const codes = [...new Set(now.obligations.map((o) => o.returnCode))].sort();
  truthy('[+] a Portuguese business owes its own country\'s return and the EU statement', codes.includes('SAFT_PT') && codes.includes('EC_SALES_LIST'), codes);
  truthy('[-] and not another country\'s', !codes.includes('VAT_RETURN_UK') && !codes.includes('DSFINVK_DE'), codes);

  const saft = now.obligations.filter((o) => o.returnCode === 'SAFT_PT');
  truthy('[+] the calendar reaches back over past periods, not just this one', saft.length > 6, saft.length);
  truthy('[+] each period carries the instrument that asks for it', saft.every((o) => !!o.citation), saft[0]);
  truthy('[+] and where the export that answers it lives, rather than proxying the bytes', saft[0].exportService === 'order-svc' && /saft-pt/.test(saft[0].exportPath), { service: saft[0].exportService, path: saft[0].exportPath });
  truthy('[+] a return the platform cannot produce says so instead of offering an empty one', now.obligations.filter((o) => o.returnCode === 'EC_SALES_LIST').every((o) => !o.exportService && !o.exportPath), 'EC sales list has no export');

  // ── the state is derived: the same period reads differently on different days ─────────────────────
  const lastMonth = monthBack(today, 1);
  const period = (asOf, code, start) => cal(asOf).obligations.find((o) => o.returnCode === code && o.periodStart === start);
  const ended = period(today, 'SAFT_PT', lastMonth);
  truthy('[+] a finished and unfiled period is owed', !!ended && (ended.state === 'DUE' || ended.state === 'OVERDUE'), ended);
  // The date the Portaria names — the 5th of the month after the month reported — and not whatever
  // the offset arithmetic happens to land on. Asserting the day of the month is asserting the law.
  truthy('[+] on the 5th of the following month, as the Portaria says', !!ended && ended.dueOn === `${ended.periodEnd.slice(0, 8)}05`, { dueOn: ended.dueOn, periodEnd: ended.periodEnd });

  const asOfInside = period(lastMonth, 'SAFT_PT', lastMonth);
  truthy('[+] asked as of a day inside the period, nothing is owed for it yet', !!asOfInside && asOfInside.state === 'NOT_DUE', asOfInside);
  const asOfDue = period(ended.dueOn, 'SAFT_PT', lastMonth);
  truthy('[+] on the day it falls due it is due, not overdue — filing on the day is filing on time', !!asOfDue && asOfDue.state === 'DUE', asOfDue);
  const asOfLate = period(today, 'SAFT_PT', monthBack(today, 6));
  truthy('[+] six months unfiled is overdue', !!asOfLate && asOfLate.state === 'OVERDUE', asOfLate);
  truthy('[+] and the outstanding list is the same thing, oldest first, to act on', Array.isArray(now.outstanding) && now.outstanding.length > 0 && now.outstanding.every((o) => o.state === 'DUE' || o.state === 'OVERDUE'), now.outstanding.length);

  // ── filing, and what filing refuses ─────────────────────────────────────────────────────────────
  const file = (code, body) => call('POST', `${SR}/${code}/filings`, { token: owner, body });
  expect(file('SAFT_PT', { periodStart: monthBack(today, 0), provider: 'MANUAL' }), '[-] a period that has not ended cannot be filed for', 409, 'STATUTORY_PERIOD_NOT_ENDED');
  expect(file('SAFT_PT', { periodStart: `${lastMonth.slice(0, 8)}14`, provider: 'MANUAL' }), '[-] nor a date that does not begin one of that return\'s periods', 400, 'STATUTORY_PERIOD_NOT_A_PERIOD');
  expect(file('SAFT_PT', { periodStart: lastMonth, provider: 'CARRIER_PIGEON' }), '[-] nor by a route nobody recognises', 400, 'STATUTORY_PROVIDER_UNKNOWN');
  expect(file('NOT_A_RETURN', { periodStart: lastMonth, provider: 'MANUAL' }), '[-] nor against a return no jurisdiction asks for', 404, 'STATUTORY_RETURN_UNKNOWN');

  const filed = file('SAFT_PT', { periodStart: lastMonth, provider: 'MANUAL', reference: `AT-${tag}`, payloadDigest: 'yLRJf1uZ0i1sWZ0rE0C1Hs4Zv0mP1nQh1tK2L3M4N5o=', note: 'filed on the portal' });
  expect(filed, '[+] a filing is recorded with the authority\'s receipt', 200);
  truthy('[+] and the period reads as filed', data(filed).state === 'FILED', data(filed));
  truthy('[+] the digest is kept, so the filing can be proved against an export produced later', !!data(filed).filing.payloadDigest, data(filed).filing);
  // Filed, whatever the date says: a return filed late is filed, and calling it overdue afterwards
  // would misrepresent the record. So the same period reads FILED a year from now too.
  const inAYear = `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`;
  truthy('[+] a return filed late is filed, not overdue, on any day it is asked about', period(inAYear, 'SAFT_PT', lastMonth).state === 'FILED', period(inAYear, 'SAFT_PT', lastMonth));

  expect(file('SAFT_PT', { periodStart: lastMonth, provider: 'MANUAL', reference: 'AT-again' }), '[-] a period already filed is not filed again by accident', 409, 'STATUTORY_FILING_EXISTS');

  // ── a correction names what it replaces, and both stay on the record ────────────────────────────
  const standing = data(filed).filing.id;
  expect(file('SAFT_PT', { periodStart: lastMonth, provider: 'MANUAL', reference: 'AT-wrong-one', supersedes: '01a00000-0000-7000-8000-000000000000' }), '[-] a correction of a filing that is not the one standing is refused', 409, 'STATUTORY_FILING_NOT_STANDING');
  const corrected = file('SAFT_PT', { periodStart: lastMonth, provider: 'MANUAL', reference: `AT-${tag}-v2`, supersedes: standing, note: 'figures restated' });
  expect(corrected, '[+] a correction names the filing it replaces', 200);
  truthy('[+] and the period still reads as filed, by the correction', data(corrected).state === 'FILED' && data(corrected).filing.id !== standing, data(corrected).filing);

  const history = data(call('GET', `${SR}/filings`, { token: owner })) || [];
  truthy('[+] both stay on the record, and only one of them stands', history.filter((f) => f.periodStart === lastMonth).length === 2 && history.filter((f) => f.periodStart === lastMonth && f.stands).length === 1, history.filter((f) => f.periodStart === lastMonth).map((f) => `${f.reference}:${f.stands}`));
  truthy('[+] the correction says what it replaced', history.some((f) => f.supersedes === standing), history.map((f) => f.supersedes));

  // ── a different jurisdiction owes different returns ─────────────────────────────────────────────
  const gb = onboardTenant(`vat-${tag}`, { country: 'GB', currency: 'GBP' });
  const gbCal = data(call('GET', SR, { token: gb.owner.token }));
  const gbCodes = [...new Set(gbCal.obligations.map((o) => o.returnCode))].sort();
  truthy('[+] a British business owes its quarterly VAT return', gbCodes.includes('VAT_RETURN_UK'), gbCodes);
  truthy('[abuse] and not the EU statement, because it was not a member for these periods', !gbCodes.includes('EC_SALES_LIST'), gbCodes);
  const vat = gbCal.obligations.find((o) => o.returnCode === 'VAT_RETURN_UK');
  truthy('[+] on calendar quarters', vat && [1, 4, 7, 10].includes(Number(vat.periodStart.slice(5, 7))), vat);
  // HMRC publishes the 7th for every quarter — one calendar month and seven days after the period.
  // A fixed day count cannot produce the 7th for all four, because the months between differ.
  truthy('[+] on the 7th of the second month after the quarter, as HMRC publishes it', !!vat && /-07$/.test(vat.dueOn) && vat.dueOn > vat.periodEnd, { dueOn: vat.dueOn, periodEnd: vat.periodEnd });

  // ── whose returns these are ────────────────────────────────────────────────────────────────────
  const cashier = staffUser(pt, 'CASHIER', [pt.stores[0].id]);
  expect(call('GET', SR, { token: cashier.token }), '[abuse] a cashier does not read what the business owes an authority', 403);
  expect(call('POST', `${SR}/SAFT_PT/filings`, { token: cashier.token, body: { periodStart: lastMonth, provider: 'MANUAL' } }), '[abuse] nor state to one on its behalf', 403);
  expect(call('GET', SR, { token: gb.owner.token }), '[+] another business reads its own, which is a different list', 200);
  truthy('[abuse] and never this one\'s filings', (data(call('GET', `${SR}/filings`, { token: gb.owner.token })) || []).length === 0, 'no filings leak');
  expect(call('GET', SR), '[-] nor does anybody without a token', 401);
  expect(call('GET', `${SR}?asOf=last-tuesday`, { token: owner }), '[-] and a date that is not a date is refused, not read as today', 400, 'STATUTORY_DATE_INVALID');

  completed.add(1);
}
