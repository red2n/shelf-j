// Subscription billing and invoicing (21.9), through the gateway: the platform fills in who it
// invoices as, sets the rates it has to charge, and then a business that signs up is subscribed to
// the plan it landed on and billed for it in advance. The run raises the invoice; the invoice is
// numbered gaplessly, never edited, and carries the tax treatment the buyer's country and VAT
// number decide — four of them, and the one that is easy to get wrong is a business in another
// member state with no *checked* number, which is charged its own country's rate and not the
// reverse charge. A mid-period upgrade bills two lines, a credit for the unused days at what was
// billed and a charge for the same days at the new price, because a net figure cannot be checked by
// the person paying it. A downgrade waits for the period already paid for. Money recorded against
// an invoice settles it; an unpaid one can be withdrawn and keeps its number either way.
// Refused: billing with no seller details, a rate the platform has not set, a business recording its
// own payment or voiding its own invoice, another business's invoice, a cashier, an anonymous caller.
//
//   k6/run.sh billing-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, newId, onboardTenant, platformAdmin, poll, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const PLANS = '/api/tenant-svc/platform/plans';
const BILLING = '/api/tenant-svc/platform/billing';
const MINE = '/api/tenant-svc/admin/tenant/billing';

export function setup() {
  return { admin: platformAdmin() };
}

export default function ({ admin }) {
  const tag = uniq().toUpperCase().slice(0, 8);
  const root = admin.token;
  const asRoot = (method, path, body) => call(method, `${BILLING}${path}`, { token: root, body });

  // ── nothing is billed until the platform says who it is ─────────────────────────────────────────
  expect(asRoot('GET', '/profile'), '[-] with no seller details there is no profile to read', 409, 'BILLING_PROFILE_NOT_SET');
  expect(asRoot('POST', '/run'), '[-] and nothing can be billed', 409, 'BILLING_PROFILE_NOT_SET');

  const profile = asRoot('PUT', '/profile', {
    legalName: `StoreQL Platform ${tag} Ltd`,
    addressLine1: '1 Quay Street',
    city: 'Dublin',
    postcode: 'D02 XY45',
    country: 'IE',
    vatNumber: `IE${tag}X`,
    companyNumber: `IE-CO-${tag}`,
    invoicePrefix: 'INV',
    paymentTermsDays: 14,
    taxRate: '0.2300',
    bankDetails: 'IBAN IE00 SHEL 0000 0000',
  });
  expect(profile, '[+] the platform fills in who it invoices as', 200);
  truthy('[+] and reads it back', data(asRoot('GET', '/profile')).invoicePrefix === 'INV', data(asRoot('GET', '/profile')));

  // ── a plan to be billed for ─────────────────────────────────────────────────────────────────────
  const planBody = (code, interval) => ({ code, name: `${code} plan`, description: 'sold to shops', billingInterval: interval, trialDays: 0, isPublic: true, sortOrder: 1 });
  const sell = (code, amount) => {
    const p = must(call('POST', PLANS, { token: root, body: planBody(code, 'MONTH') }), 201, `plan ${code}`);
    must(call('POST', `${PLANS}/${p.id}/prices`, { token: root, body: { currency: 'EUR', amount } }), 200, `price ${code}`);
    must(call('POST', `${PLANS}/${p.id}/activate`, { token: root }), 200, `sell ${code}`);
    return p.id;
  };
  const small = sell(`BILL-S-${tag}`, 10);
  const big = sell(`BILL-B-${tag}`, 20);
  must(call('POST', `${PLANS}/${small}/default`, { token: root }), 200, 'default plan');

  // ── a business signs up, and signing up is subscribing ──────────────────────────────────────────
  const shop = onboardTenant(`billing-${tag}`, { country: 'IE', currency: 'EUR' });
  const owner = shop.owner.token;
  const mine = () => data(call('GET', MINE, { token: owner }));
  // Signing up subscribes the business inside the same call, so this is a guard and not a wait.
  let sub = mine();
  poll(60, () => {
    sub = mine();
    return !!(sub.subscription && sub.subscription.id);
  });
  truthy('[+] a business that signs up is subscribed to the plan it landed on', sub.subscription && sub.subscription.planCode === `BILL-S-${tag}`, sub.subscription);
  truthy('[+] at the price it was sold, locked on the subscription', Number(sub.subscription.priceAmount) === 10, sub.subscription);
  truthy('[+] active from the day it signed up, with no trial on this plan', sub.subscription.status === 'ACTIVE' && !sub.subscription.trialEnd, sub.subscription);
  truthy('[+] and its history says so', (sub.events || []).some((e) => e.kind === 'STARTED'), sub.events);

  // ── the first period was billed in advance, the day it started ──────────────────────────────────
  const invoices = () => data(call('GET', `${MINE}/invoices`, { token: owner })) || [];
  const first = invoices()[0];
  truthy('[+] the first period is invoiced in advance, not after it', !!first && first.status === 'OPEN', invoices());
  truthy('[+] numbered, and the number says which year', /^INV-\d{4}-\d{6}$/.test(first.number), first.number);
  truthy('[+] a business in the platform\'s own country pays the platform\'s own rate', first.taxTreatment === 'DOMESTIC' && Number(first.taxRate) === 0.23, first);
  truthy('[+] and the arithmetic adds up', Math.abs(Number(first.netAmount) + Number(first.taxAmount) - Number(first.totalAmount)) < 0.005, first);
  truthy('[+] due by the terms the platform set, not the day it was issued', first.dueDate > first.issueDate, first);

  const file = (id, token) => data(call('GET', `${MINE}/invoices/${id}`, { token: token || owner }));
  const firstFile = file(first.id);
  truthy('[+] it prints both sides as they stood that day', /StoreQL Platform/.test(firstFile.sellerSnapshot) && !!firstFile.buyerSnapshot, { seller: firstFile.sellerSnapshot, buyer: firstFile.buyerSnapshot });
  truthy('[+] one line for the period, naming it', (firstFile.lines || []).length === 1 && firstFile.lines[0].kind === 'PLAN', firstFile.lines);
  truthy('[+] no reverse-charge wording on a domestic invoice', !firstFile.taxNote, firstFile.taxNote);

  // ── the four tax treatments ─────────────────────────────────────────────────────────────────────
  const details = (body) => call('PUT', `${MINE}/details`, { token: owner, body });
  // Another member state, no checked number: its own country's rate, and refused until one is set.
  expect(details({ country: 'DE', name: `Weinhaus ${tag}` }), '[+] the business says where it is established', 200);
  truthy('[+] a number it gave but nobody checked does not count', mine().subscription.buyer.vatChecked === false, mine().subscription.buyer);
  // A business the platform has no rate for is passed over and named, not silently unbilled — and
  // not allowed to stop everybody else's invoices either.
  const passedOver = asRoot('POST', '/run?asOf=2027-01-05');
  expect(passedOver, '[+] a business the platform has no rate for does not stop the run', 200);
  truthy('[+] it is passed over and named, so somebody can act on it', (data(passedOver).skipped || []).some((k) => k.code === 'BILLING_RATE_NOT_SET' && k.tenantId === shop.tenantId), data(passedOver).skipped);
  // A single business's own move refuses loudly, because somebody is waiting on the answer.
  expect(call('POST', `${MINE}/plan`, { token: owner, body: { planId: big, when: 'NOW' } }), '[-] and its own upgrade refuses until the rate is set', 409, 'BILLING_RATE_NOT_SET');
  expect(asRoot('PUT', '/vat-rates', { country: 'DE', effectiveFrom: '2020-01-01', rate: '0.1900', note: 'standard' }), '[+] the platform sets Germany\'s rate', 200);
  truthy('[+] and lists what it will charge, and where', (data(asRoot('GET', '/vat-rates')) || []).some((r) => r.country === 'DE' && Number(r.rate) === 0.19), data(asRoot('GET', '/vat-rates')));

  // ── a mid-period upgrade: two lines, never one net figure ───────────────────────────────────────
  // Back to the platform's own country, so the upgrade is taxed domestically and can be checked.
  expect(details({ country: 'IE', name: `Billing ${tag} Ltd` }), '[+] the business corrects where it is', 200);
  const up = call('POST', `${MINE}/plan`, { token: owner, body: { planId: big, when: 'NOW' } });
  expect(up, '[+] the business moves up, and has the bigger plan at once', 200);
  truthy('[+] on the new plan and at the new price', data(up).subscription.planCode === `BILL-B-${tag}` && Number(data(up).subscription.priceAmount) === 20, data(up).subscription);
  const prorated = invoices().find((i) => i.id !== first.id);
  const proratedFile = file(prorated.id);
  const kinds = (proratedFile.lines || []).map((l) => l.kind).sort();
  truthy('[+] a proration is a credit and a charge, so the figures can be checked', kinds.join(',') === 'CREDIT,PRORATION', proratedFile.lines);
  const credit = proratedFile.lines.find((l) => l.kind === 'CREDIT');
  const charge = proratedFile.lines.find((l) => l.kind === 'PRORATION');
  truthy('[+] the credit gives back, the charge takes', Number(credit.amount) < 0 && Number(charge.amount) > 0, { credit: credit.amount, charge: charge.amount });
  truthy('[+] both name the same days, so they are comparable', /days/.test(credit.description) && /days/.test(charge.description), [credit.description, charge.description]);
  truthy('[+] the credit is of what was billed, not of what the plan costs today', Math.abs(Number(charge.amount)) > Math.abs(Number(credit.amount)), { credit: credit.amount, charge: charge.amount });

  // ── a downgrade waits for the period already paid for ───────────────────────────────────────────
  const down = call('POST', `${MINE}/plan`, { token: owner, body: { planId: small, when: 'PERIOD_END' } });
  expect(down, '[+] the business moves down at the end of the period', 200);
  truthy('[+] still on what it paid for, with the change waiting', data(down).subscription.planCode === `BILL-B-${tag}` && !!data(down).subscription.pendingPlanId, data(down).subscription);
  truthy('[+] and its history says when', (data(down).events || []).some((e) => e.kind === 'DOWNGRADE_SCHEDULED'), data(down).events);
  expect(call('POST', `${MINE}/plan/cancel-pending`, { token: owner }), '[+] and it can drop the change', 200);
  expect(call('POST', `${MINE}/plan/cancel-pending`, { token: owner }), '[-] but only while one is waiting', 409, 'NO_PLAN_CHANGE_PENDING');

  // ── ending, and changing its mind ───────────────────────────────────────────────────────────────
  expect(call('POST', `${MINE}/cancel`, { token: owner, body: { reason: 'closing the shop' } }), '[+] it ends the subscription at the period end', 200);
  expect(call('POST', `${MINE}/cancel`, { token: owner, body: {} }), '[-] and only once', 409, 'SUBSCRIPTION_ALREADY_ENDING');
  expect(call('POST', `${MINE}/resume`, { token: owner }), '[+] it changes its mind while the period runs', 200);
  expect(call('POST', `${MINE}/resume`, { token: owner }), '[-] and there is then nothing to undo', 409, 'SUBSCRIPTION_NOT_ENDING');

  // ── money against an invoice ────────────────────────────────────────────────────────────────────
  const pay = (id, amount, ref) => call('POST', `${BILLING}/invoices/${id}/payments`, { token: root, body: { amount, method: 'BANK_TRANSFER', provider: 'bank', providerRef: ref } });
  expect(pay(first.id, Number(first.totalAmount), `REF-${tag}-1`), '[+] the platform records what arrived', 200);
  truthy('[+] and the invoice is settled', file(first.id).invoice.status === 'PAID' && Number(file(first.id).invoice.outstanding) === 0, file(first.id).invoice);
  expect(pay(first.id, 1, `REF-${tag}-2`), '[-] a settled invoice takes no more money', 409, 'INVOICE_NOT_OPEN');
  expect(call('POST', `${BILLING}/invoices/${first.id}/void`, { token: root, body: { reason: 'too late' } }), '[-] nor is a paid invoice withdrawn; it is credited', 409, 'INVOICE_NOT_VOIDABLE');

  const voidable = invoices().find((i) => i.status === 'OPEN');
  expect(call('POST', `${BILLING}/invoices/${voidable.id}/void`, { token: root, body: { reason: `raised in error ${tag}` } }), '[+] an unpaid invoice is withdrawn with a reason', 200);
  truthy('[+] and keeps its number — a gap is what an auditor asks about', file(voidable.id).invoice.number === voidable.number && file(voidable.id).invoice.status === 'VOID', file(voidable.id).invoice);
  truthy('[+] the reason travels with it', /raised in error/.test(file(voidable.id).invoice.voidedReason || ''), file(voidable.id).invoice.voidedReason);

  // ── the receivables ─────────────────────────────────────────────────────────────────────────────
  const owed = data(asRoot('GET', '/receivables?limit=100')) || [];
  truthy('[+] a settled invoice is not owed', !owed.some((i) => i.id === first.id), owed.length);
  truthy('[+] nor is a withdrawn one', !owed.some((i) => i.id === voidable.id), owed.length);

  // ── the test clock, which is why this flow can exist ────────────────────────────────────────────
  const ran = asRoot('POST', '/run?asOf=2027-06-01');
  expect(ran, '[+] the run can be asked for a day, where that is switched on', 200);
  truthy('[+] and says what it raised', typeof data(ran).invoicesRaised === 'number' && data(ran).asOf === '2027-06-01', data(ran));
  expect(asRoot('POST', '/run?asOf=not-a-day'), '[-] a day is a date', 400, 'BILLING_DATE_INVALID');

  // ── whose books these are ───────────────────────────────────────────────────────────────────────
  const other = onboardTenant(`billing-other-${tag}`, { country: 'IE', currency: 'EUR' });
  const cashier = staffUser(shop, 'CASHIER', [shop.stores[0].id]);
  expect(call('GET', `${MINE}/invoices/${first.id}`, { token: other.owner.token }), '[abuse] another business\'s invoice is not found, not forbidden', 404, 'INVOICE_NOT_FOUND');
  expect(call('POST', `${BILLING}/invoices/${first.id}/payments`, { token: owner, body: { amount: 1, method: 'BANK_TRANSFER' } }), '[abuse] a business does not record its own payment', 403);
  expect(call('POST', `${BILLING}/invoices/${first.id}/void`, { token: owner, body: { reason: 'mine now' } }), '[abuse] nor withdraw its own invoice', 403);
  expect(call('GET', `${BILLING}/receivables`, { token: owner }), '[abuse] nor read everybody\'s receivables', 403);
  expect(call('PUT', `${BILLING}/profile`, { token: owner, body: { legalName: 'Not The Platform', country: 'IE', invoicePrefix: 'X', paymentTermsDays: 1, taxRate: '0.0000' } }), '[abuse] nor say who the platform is', 403);
  expect(call('GET', MINE, { token: cashier.token }), '[abuse] a cashier does not read what the business pays', 403);
  expect(call('GET', MINE), '[-] nor does anybody without a token', 401);
  expect(call('GET', `${MINE}/invoices/${newId()}`, { token: owner }), '[-] an invoice that does not exist is not found', 404, 'INVOICE_NOT_FOUND');

  completed.add(1);
}
