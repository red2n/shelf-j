// Chargeback and dispute handling (11.9), through the gateway: a card payment taken at the till
// is charged back — the acquirer writes, a manager records it — and the business is told at once
// and by when it must answer; the ledger moves the money out of card clearing into card receipts
// in dispute and books the acquirer's fee; the answer is given once and in time; a dispute won
// brings the money back, one lost or accepted writes it off; the register pages, filters and
// reports the ratio the card schemes watch. Refused: a cash payment charged back, more than was
// paid, a second dispute while one is open, an answer twice or after its date, an outcome decided
// twice, another business's payment or dispute, a cashier anywhere near it, an anonymous caller.
//
// The local stack runs the MANUAL payment provider, so disputes are recorded from the acquirer's
// notice; a provider's own disputes arrive by signed webhook and are covered by payment-svc's
// integration tests (DisputeIT, StripeDisputeParsingTest).
//
//   k6/run.sh chargeback-flow
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, poll, sellingTenant, truthy } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const DISPUTES = '/api/payment-svc/admin/disputes';
const LEDGER = '/api/purchase-svc/nominal-ledger';
const CARD_CLEARING = '1250';
const IN_DISPUTE = '1255';
const LOSSES = '6510';
const FEES = '6511';

export function setup() {
  return sellingTenant('chargeback', { price: '20.00', costPrice: '8.00' });
}

export default function ({ tenant, rival, store, variantId, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const round = (v) => Math.round(v * 100) / 100;
  const inDays = (n) => new Date(Date.now() + n * 86400 * 1000).toISOString();
  const sale = (qty) => must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty }] } }), 201, 'till sale');
  const pay = (orderId, amount, method) => must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId, amount, method, storeId: store.id } }), [200, 201], `${method} tender`);
  const record = (body, token = owner, idem) => call('POST', DISPUTES, { token, idem, body });
  const chargeback = (paymentId, extra = {}) => ({ paymentId, feeAmount: 15, reason: 'PRODUCT_NOT_RECEIVED', networkReasonCode: '13.1', caseReference: `CB-${paymentId.slice(-6)}`, evidenceDueBy: inDays(10), ...extra });
  const chargebackLines = (orderId) => {
    const all = data(call('GET', `${LEDGER}?limit=100`, { token: owner }));
    return (Array.isArray(all) ? all : []).filter((l) => l.sourceRef === orderId && l.sourceType === 'CHARGEBACK');
  };
  const net = (lines, code) => round(lines.filter((l) => l.nominalCode === code).reduce((t, l) => t + num(l.debit) - num(l.credit), 0));

  // ── a card sale, charged back ────────────────────────────────────────────────────────────────────
  const first = sale(2);
  const total = num(first.total);
  const card = pay(first.id, total.toFixed(2), 'CARD');

  const recorded = record(chargeback(card.id), owner, 'cb-first');
  expect(recorded, '[+] a manager records the chargeback the acquirer wrote about', 201);
  const dispute = data(recorded);
  truthy('[+] it needs an answer, by the acquirer\'s date, for the whole payment', dispute.status === 'NEEDS_RESPONSE' && num(dispute.amount) === total && !!dispute.evidenceDueBy && dispute.overdue === false, dispute);
  truthy('[+] it is the acquirer\'s, not a provider\'s: MANUAL, under its case number', dispute.provider === 'MANUAL' && dispute.reference === `CB-${card.id.slice(-6)}` && dispute.fundsWithdrawn === true, dispute);
  truthy('[+] the same request again is the same chargeback', data(record(chargeback(card.id), owner, 'cb-first')).id === dispute.id);
  expect(record(chargeback(card.id)), '[-] a second dispute on a payment that has one open', 409, 'DISPUTE_ALREADY_OPEN');

  let lines = [];
  const posted = poll(60, () => {
    lines = chargebackLines(first.id);
    return lines.length >= 3;
  });
  truthy('[+] the ledger hears of it', posted >= 0, { posted, lines: lines.length });
  truthy('[+] the money leaves card clearing for card receipts in dispute', net(lines, IN_DISPUTE) === total && net(lines, CARD_CLEARING) === round(-(total + 15)), { inDispute: net(lines, IN_DISPUTE), clearing: net(lines, CARD_CLEARING) });
  truthy('[+] and the acquirer\'s fee is an expense the day it is charged', net(lines, FEES) === 15, net(lines, FEES));

  let alert = null;
  const told = poll(60, () => {
    const log = data(call('GET', `/api/notification-svc/admin/notifications?recipient=${store.id}`, { token: owner })) || [];
    alert = (Array.isArray(log) ? log : []).find((n) => n.type === 'PAYMENT_DISPUTE_OPENED');
    return !!alert;
  });
  truthy('[+] the business is told at once, and by when it must answer', told >= 0 && /Chargeback/.test(alert.subject || '') && /Answer it on the Disputes screen by/.test(alert.body || ''), alert);

  // ── the answer ───────────────────────────────────────────────────────────────────────────────────
  expect(call('POST', `${DISPUTES}/${dispute.id}/evidence`, { token: owner, body: {} }), '[-] an answer has to say something', 400, 'DISPUTE_EVIDENCE_EMPTY');
  const answered = call('POST', `${DISPUTES}/${dispute.id}/evidence`, { token: owner, body: { productDescription: 'Two crates of oranges', customerName: 'A. Shopper', receiptReference: first.id, fulfilmentProof: 'Collected at the till, signed' } });
  expect(answered, '[+] the business answers with its evidence', 200);
  truthy('[+] and the dispute is with the bank', data(answered).dispute.status === 'UNDER_REVIEW' && data(answered).evidence.receiptReference === first.id, data(answered).dispute);
  expect(call('POST', `${DISPUTES}/${dispute.id}/evidence`, { token: owner, body: { notes: 'and another thing' } }), '[abuse] a scheme takes evidence once', 409, 'DISPUTE_NOT_AWAITING_RESPONSE');

  // ── won ──────────────────────────────────────────────────────────────────────────────────────────
  expect(call('POST', `${DISPUTES}/${dispute.id}/resolve`, { token: owner, body: { outcome: 'PERHAPS' } }), '[-] an outcome is won or lost', 400, 'DISPUTE_OUTCOME_UNKNOWN');
  const won = call('POST', `${DISPUTES}/${dispute.id}/resolve`, { token: owner, body: { outcome: 'WON', note: 'Acquirer letter' } });
  expect(won, '[+] the acquirer finds for the business', 200);
  const history = (data(won).history || []).map((h) => h.kind);
  truthy('[+] every step is in its history, in order', JSON.stringify(history) === '["OPENED","FUNDS_WITHDRAWN","EVIDENCE_SUBMITTED","WON","FUNDS_REINSTATED"]', history);
  expect(call('POST', `${DISPUTES}/${dispute.id}/resolve`, { token: owner, body: { outcome: 'LOST' } }), '[abuse] a dispute is decided once', 409, 'DISPUTE_CLOSED');
  expect(call('POST', `${DISPUTES}/${dispute.id}/accept`, { token: owner }), '[abuse] and cannot be accepted after it is won', 409, 'DISPUTE_CLOSED');
  const back = poll(60, () => {
    lines = chargebackLines(first.id);
    return net(lines, IN_DISPUTE) === 0 && lines.length >= 5;
  });
  truthy('[+] the money comes back into card clearing; the fee does not', back >= 0 && net(lines, CARD_CLEARING) === -15 && net(lines, LOSSES) === 0, { clearing: net(lines, CARD_CLEARING), inDispute: net(lines, IN_DISPUTE) });

  // ── lost, accepted, and late ─────────────────────────────────────────────────────────────────────
  const second = sale(1);
  const secondCard = pay(second.id, num(second.total).toFixed(2), 'CARD');
  const partial = must(record(chargeback(secondCard.id, { amount: 5, feeAmount: 0 })), 201, 'a partial chargeback');
  truthy('[+] a chargeback may be for part of a payment', num(partial.amount) === 5);
  expect(call('POST', `${DISPUTES}/${partial.id}/accept`, { token: owner }), '[+] not worth contesting: accepted', 200);
  const writtenOff = poll(60, () => net(chargebackLines(second.id), LOSSES) === 5);
  truthy('[+] accepted is lost: written off to chargeback losses', writtenOff >= 0 && net(chargebackLines(second.id), IN_DISPUTE) === 0, chargebackLines(second.id).length);
  expect(record(chargeback(secondCard.id, { amount: 5, caseReference: 'CB-second-presentment' })), '[+] a closed dispute does not stop a later one on the same payment', 201);

  const third = sale(1);
  const thirdCard = pay(third.id, num(third.total).toFixed(2), 'CARD');
  // The deadline has to be in the future to be recorded at all, so this waits past it rather than
  // setting it in the past. The margin is deliberately generous: k6 runs on the host and the service
  // in a container, and their clocks differ by hundreds of milliseconds on this machine (the same
  // divergence SJ-D66 exposed in the gateway's token verifier). A 2.5s deadline with a 3s sleep left
  // 500ms of margin and lost the race. Three seconds of margin measures the rule; half a second
  // measures the clocks.
  const hurried = must(record(chargeback(thirdCard.id, { evidenceDueBy: new Date(Date.now() + 1500).toISOString() })), 201, 'a chargeback due in moments');
  sleep(4.5);
  expect(call('POST', `${DISPUTES}/${hurried.id}/evidence`, { token: owner, body: { notes: 'Here is the receipt' } }), '[abuse] an answer after its date is refused', 409, 'DISPUTE_EVIDENCE_LATE');
  truthy('[+] and the register says the dispute is overdue', data(call('GET', `${DISPUTES}/${hurried.id}`, { token: owner })).dispute.overdue === true);
  expect(call('POST', `${DISPUTES}/${hurried.id}/resolve`, { token: owner, body: { outcome: 'LOST' } }), '[+] what is left is to say it was lost', 200);

  // ── what is not a chargeback ─────────────────────────────────────────────────────────────────────
  const fourth = sale(1);
  const cash = pay(fourth.id, num(fourth.total).toFixed(2), 'CASH');
  expect(record(chargeback(cash.id)), '[-] no bank takes cash back', 409, 'DISPUTE_NOT_DISPUTABLE');
  expect(record(chargeback(thirdCard.id, { amount: num(third.total) + 0.01 })), '[-] nor more than was paid', 400, 'DISPUTE_AMOUNT_EXCEEDS_PAYMENT');
  expect(record(chargeback(thirdCard.id, { evidenceDueBy: inDays(-1) })), '[-] a date already past', 400, 'DISPUTE_DUE_DATE_PAST');
  expect(record(chargeback(thirdCard.id, { reason: 'BUYERS_REMORSE' })), '[-] a reason no scheme gives', 400, 'DISPUTE_REASON_UNKNOWN');
  expect(record({ paymentId: thirdCard.id }), '[-] a chargeback without its case number or date', 400);
  expect(record(chargeback('01a090ae-611e-7011-ae7d-1bd68c966ff6')), '[-] a payment that does not exist', 404, 'PAYMENT_NOT_FOUND');

  // ── whose it is ──────────────────────────────────────────────────────────────────────────────────
  expect(record(chargeback(thirdCard.id), cashier.token), '[abuse] a cashier records no chargeback', 403);
  expect(call('GET', DISPUTES, { token: cashier.token }), '[abuse] and reads none', 403);
  expect(call('GET', DISPUTES), '[-] nor does anybody without a token', 401);
  expect(record(chargeback(thirdCard.id), rival.owner.token), '[abuse] another business cannot dispute this one\'s payment', 404, 'PAYMENT_NOT_FOUND');
  expect(call('GET', `${DISPUTES}/${dispute.id}`, { token: rival.owner.token }), '[abuse] or read its disputes', 404, 'DISPUTE_NOT_FOUND');
  expect(call('POST', `${DISPUTES}/${hurried.id}/accept`, { token: rival.owner.token }), '[abuse] or close them', 404, 'DISPUTE_NOT_FOUND');
  truthy('[+] the rival\'s own register is empty', (data(call('GET', DISPUTES, { token: rival.owner.token })) || []).length === 0);

  // ── the register and the ratio ───────────────────────────────────────────────────────────────────
  const page = call('GET', `${DISPUTES}?limit=2`, { token: owner });
  const pageRows = data(page);
  truthy('[+] the register is newest first, a page at a time', pageRows.length === 2 && !!JSON.parse(page.body).meta.nextCursor, pageRows.length);
  const rest = data(call('GET', `${DISPUTES}?limit=2&after=${JSON.parse(page.body).meta.nextCursor}`, { token: owner }));
  truthy('[+] and the next page carries on where it stopped', rest.length === 2 && !pageRows.some((r) => rest.some((o) => o.id === r.id)), rest.map((r) => r.id));
  truthy('[+] it filters by how a dispute stands', data(call('GET', `${DISPUTES}?status=WON`, { token: owner })).length === 1 && data(call('GET', `${DISPUTES}?status=NEEDS_RESPONSE&storeId=${store.id}`, { token: owner })).length === 1);
  expect(call('GET', `${DISPUTES}?status=PENDING`, { token: owner }), '[-] by a status that exists', 400, 'DISPUTE_STATUS_UNKNOWN');

  const summary = must(call('GET', `${DISPUTES}/summary?from=${inDays(-1)}&to=${inDays(1)}`, { token: owner }), 200, 'summary');
  truthy('[+] the period: four opened, one won, two lost', summary.opened === 4 && summary.won === 1 && summary.lost === 2 && summary.needsResponse === 1, summary);
  truthy('[+] against three card payments, a ratio far past where the schemes start watching', summary.cardPayments === 3 && num(summary.disputeRatio) > 1 && summary.aboveMonitoringThreshold === true, summary);
  truthy('[+] what it cost: the amounts lost and the fees', num(summary.amountLost) === round(5 + num(third.total)) && num(summary.feesCharged) === 45, summary);
  expect(call('GET', `${DISPUTES}/summary?from=${inDays(1)}&to=${inDays(-1)}`, { token: owner }), '[-] a period that ends before it begins', 400, 'DISPUTE_PERIOD_INVALID');

  completed.add(1);
}
