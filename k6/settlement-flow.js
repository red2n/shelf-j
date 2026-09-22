// Payment reconciliation against the acquirer's settlement file (11.10), through the gateway: card
// payments taken at the till, one part-refunded and one charged back, are paid out by the acquirer
// in a batch, less its fees — and the payout's file is imported. A file in which every line matches
// is reconciled at once and the ledger empties card clearing into the bank, books the acquirer's
// fees, and does not book the chargeback's fee twice. A file with exceptions — a payment settled
// short, a reference typed wrong, money that answers to nothing, a payment settled twice, a reserve
// held back — waits for a manager to decide each line and sign the payout off, and only then is the
// ledger told. The card payments no payout has covered are listed until one does. Stripe's and
// Adyen's own layouts are read, and name their payout, its date and its currency themselves.
// Refused: a file that does not add up, the wrong layout, a refund written as money in, a payout
// imported twice, a payout in another currency, a full card number anywhere in a file (at the
// gateway), a file past the line cap or the route's size cap, another business's settlement or
// payment, a line decided after the books have it, a cashier anywhere near it, an anonymous caller;
// ten imports of one payout at once make one batch.
//
//   k6/run.sh settlement-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, BASE, call, data, expect, must, newId, poll, sellingTenant, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const SETTLEMENTS = '/api/payment-svc/admin/settlements';
const DISPUTES = '/api/payment-svc/admin/disputes';
const LEDGER = '/api/purchase-svc/nominal-ledger';
const BANK = '1200';
const CARD_CLEARING = '1250';
const IN_DISPUTE = '1255';
const UNALLOCATED = '1299';
const CARD_FEES = '6500';
const CHARGEBACK_FEES = '6511';
const HEADER = 'type,reference,original_reference,gross,fee,net\n';

export function setup() {
  return sellingTenant('settlement', { price: '20.00', costPrice: '8.00' });
}

export default function ({ tenant, rival, store, variantId, cashier }) {
  const owner = tenant.owner.token;
  const tag = uniq().toUpperCase();
  const num = (v) => Number(v || 0);
  const round = (v) => Math.round(v * 100) / 100;
  const money = (v) => round(v).toFixed(2);
  const today = new Date().toISOString().slice(0, 10);
  const sale = (qty) => must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty }] } }), 201, 'till sale');
  const pay = (orderId, amount, method, reference) => must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId, amount, method, reference, storeId: store.id } }), [200, 201], `${method} tender`);
  const cardSale = (qty, reference) => {
    const order = sale(qty);
    return { order, total: num(order.total), payment: pay(order.id, num(order.total).toFixed(2), 'CARD', reference), reference };
  };
  const payout = (reference, lines, extra = {}) => {
    const net = round(lines.reduce((t, l) => t + l.gross - (l.fee || 0), 0));
    const content = HEADER + lines.map((l) => `${l.type},${l.reference || ''},${l.original || ''},${money(l.gross)},${money(l.fee || 0)},${money(l.gross - (l.fee || 0))}`).join('\n') + '\n';
    return { body: { provider: 'Worldpay', format: 'STOREQL', reference, payoutDate: today, declaredNet: net, content, ...extra }, net };
  };
  const importFile = (body, token = owner, idem = true) => call('POST', SETTLEMENTS, { token, idem, body });
  const batch = (id, query = '', token = owner) => call('GET', `${SETTLEMENTS}/${id}${query}`, { token });
  const resolve = (batchId, lineId, body, token = owner) => call('POST', `${SETTLEMENTS}/${batchId}/lines/${lineId}/resolve`, { token, body });
  const lineOf = (file, reference) => (file.lines || []).find((l) => l.reference === reference);
  const balances = () => {
    const tb = data(call('GET', `${LEDGER}/trial-balance`, { token: owner })) || {};
    const of = (code) => round(num(((tb.rows || []).find((r) => r.nominalCode === code) || {}).balance));
    return { balanced: tb.balanced === true, of };
  };
  const unsettled = (query = 'olderThanDays=0') => data(call('GET', `${SETTLEMENTS}/unsettled?${query}&limit=100`, { token: owner })) || [];
  const fee = (gross) => round(gross * 0.015);

  // ── the day's card takings ───────────────────────────────────────────────────────────────────────
  const a = cardSale(1, `A-${tag}`);
  const b = cardSale(2, `B-${tag}`);
  const c = cardSale(3, `C-${tag}`);
  const cashOrder = sale(1);
  const cash = pay(cashOrder.id, num(cashOrder.total).toFixed(2), 'CASH');
  expect(call('POST', `/api/payment-svc/payments/by-order/${b.order.id}/refunds`, { token: owner, idem: true, body: { paymentId: b.payment.id, amount: '5.00', method: 'CARD', reference: `RB-${tag}`, reason: 'one was bruised' } }), '[+] part of a card payment is refunded', [200, 201]);
  const caseRef = `CASE-${tag}`;
  const dispute = must(call('POST', DISPUTES, { token: owner, idem: true, body: { paymentId: c.payment.id, feeAmount: 15, reason: 'FRAUDULENT', caseReference: caseRef, evidenceDueBy: new Date(Date.now() + 10 * 86400 * 1000).toISOString() } }), 201, 'a chargeback on the third');

  const waiting = unsettled();
  truthy('[+] the three card payments are waiting to be paid out, the cash is not', [a, b, c].every((s) => waiting.some((u) => u.paymentId === s.payment.id)) && !waiting.some((u) => u.paymentId === cash.id), waiting.length);
  truthy('[+] and none of today\'s is overdue yet', !unsettled('olderThanDays=3').some((u) => u.paymentId === a.payment.id));

  const before = poll(90, () => {
    const now = balances();
    return now.of(CARD_CLEARING) === round(a.total + b.total + c.total - 5 - (c.total + 15)) && now.of(IN_DISPUTE) === c.total;
  });
  truthy('[+] the ledger holds the takings in card clearing, less the refund and the chargeback', before >= 0, { clearing: balances().of(CARD_CLEARING), inDispute: balances().of(IN_DISPUTE) });

  // ── a payout in which everything matches ─────────────────────────────────────────────────────────
  const clean = payout(`WP-${tag}-1`, [
    { type: 'SALE', reference: a.reference, gross: a.total, fee: fee(a.total) },
    { type: 'SALE', reference: b.reference, gross: b.total, fee: fee(b.total) },
    { type: 'SALE', reference: c.reference, gross: c.total, fee: fee(c.total) },
    { type: 'REFUND', reference: `RB-${tag}`, original: b.reference, gross: -5 },
    { type: 'CHARGEBACK', reference: caseRef, original: c.reference, gross: -c.total, fee: 15 },
    { type: 'FEE', reference: 'TERMINAL-RENTAL', gross: 0, fee: 10 },
  ]);
  const cleanKey = newId();
  const imported = importFile(clean.body, owner, cleanKey);
  expect(imported, '[+] the acquirer\'s file for the payout is imported', 201);
  const first = data(imported);
  truthy('[+] every line matched, so it is reconciled at once, by nobody', first.status === 'RECONCILED' && first.openExceptions === 0 && first.lineCount === 6 && !first.reconciledBy, first);
  truthy('[+] it adds up: sales, the refund, the chargeback, the fees, and what reached the bank', num(first.salesAmount) === round(a.total + b.total + c.total) && num(first.refundAmount) === 5 && num(first.chargebackAmount) === c.total && num(first.netAmount) === clean.net && first.provider === 'WORLDPAY', first);
  const firstFile = data(batch(first.id));
  truthy('[+] each line says what it matched', lineOf(firstFile, a.reference).tenderId === a.payment.id && lineOf(firstFile, `RB-${tag}`).matchStatus === 'MATCHED' && !!lineOf(firstFile, `RB-${tag}`).refundId && lineOf(firstFile, caseRef).disputeId === dispute.id && lineOf(firstFile, 'TERMINAL-RENTAL').matchStatus === 'NOT_APPLICABLE', firstFile.lines.map((l) => l.matchStatus));
  truthy('[+] the same request again is the same payout', data(importFile(clean.body, owner, cleanKey)).id === first.id);
  expect(importFile(clean.body), '[-] the same payout from another request is refused', 409, 'SETTLEMENT_ALREADY_IMPORTED');

  const saleFees = round(fee(a.total) + fee(b.total) + fee(c.total));
  let now = balances();
  const cleared = poll(90, () => {
    now = balances();
    return now.of(CARD_CLEARING) === 0 && now.of(BANK) === clean.net;
  });
  truthy('[+] card clearing empties into the bank for exactly what was paid', cleared >= 0 && now.balanced, { clearing: now.of(CARD_CLEARING), bank: now.of(BANK), net: clean.net });
  truthy('[+] the acquirer\'s fees are an expense; the chargeback\'s fee is not booked twice', now.of(CARD_FEES) === round(saleFees + 10) && now.of(CHARGEBACK_FEES) === 15 && now.of(UNALLOCATED) === 0, { fees: now.of(CARD_FEES), chargebackFees: now.of(CHARGEBACK_FEES) });
  truthy('[+] and nothing is waiting to be paid out any more', ![a, b, c].some((s) => unsettled().some((u) => u.paymentId === s.payment.id)));

  // ── a payout with exceptions ─────────────────────────────────────────────────────────────────────
  const d = cardSale(1, `D-${tag}`);
  const e = cardSale(2, `E-${tag}`);
  const messy = payout(`WP-${tag}-2`, [
    { type: 'SALE', reference: d.reference, gross: d.total - 1, fee: 0.3 },
    { type: 'SALE', reference: `E-TYPED-WRONG-${tag}`, gross: e.total, fee: 0.6 },
    { type: 'SALE', reference: `NOBODYS-${tag}`, gross: 30, fee: 0.45 },
    { type: 'SALE', reference: a.reference, gross: a.total, fee: fee(a.total) },
    { type: 'ADJUSTMENT', reference: 'RESERVE-HELD', gross: -50 },
  ]);
  const second = must(importFile(messy.body), 201, 'a payout with exceptions');
  truthy('[+] five lines need somebody\'s decision', second.status === 'EXCEPTIONS' && second.openExceptions === 5, second);
  const open = data(batch(second.id, '?open=true'));
  truthy('[+] settled short: the payment is found and what is held is shown', lineOf(open, d.reference).matchStatus === 'AMOUNT_MISMATCH' && num(lineOf(open, d.reference).expectedAmount) === d.total && lineOf(open, d.reference).tenderId === d.payment.id, lineOf(open, d.reference));
  truthy('[+] a payment settled in an earlier payout is a duplicate, not a second settlement', lineOf(open, a.reference).matchStatus === 'DUPLICATE' && !lineOf(open, a.reference).tenderId, lineOf(open, a.reference));
  truthy('[+] what answers to nothing, and the reserve, are unmatched', lineOf(open, `NOBODYS-${tag}`).matchStatus === 'UNMATCHED' && lineOf(open, 'RESERVE-HELD').open === true);
  expect(call('POST', `${SETTLEMENTS}/${second.id}/reconcile`, { token: owner }), '[-] it cannot be signed off while anything is open', 409, 'SETTLEMENT_HAS_EXCEPTIONS');
  truthy('[+] and the ledger has heard nothing of it', balances().of(BANK) === clean.net);

  const typed = lineOf(open, `E-TYPED-WRONG-${tag}`);
  const nobodys = lineOf(open, `NOBODYS-${tag}`);
  expect(resolve(second.id, typed.id, { resolution: 'FORGIVEN', note: 'x' }), '[-] a decision that is not one', 400, 'SETTLEMENT_RESOLUTION_UNKNOWN');
  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND' }), '[-] matched by hand to nothing', 400, 'SETTLEMENT_TARGET_REQUIRED');
  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND', targetId: newId() }), '[-] to a payment that does not exist', 404, 'SETTLEMENT_TARGET_NOT_FOUND');
  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND', targetId: cash.id }), '[-] to cash, which no acquirer settles', 404, 'SETTLEMENT_TARGET_NOT_FOUND');
  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND', targetId: a.payment.id }), '[abuse] to a payment another line has settled', 409, 'SETTLEMENT_ALREADY_SETTLED');
  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND', targetId: d.payment.id }), '[abuse] or one a line in this file is holding', 409, 'SETTLEMENT_ALREADY_SETTLED');
  expect(resolve(second.id, nobodys.id, { resolution: 'UNALLOCATED' }), '[-] money sent to unallocated without saying why', 400, 'SETTLEMENT_NOTE_REQUIRED');
  expect(resolve(second.id, nobodys.id, { resolution: 'DIFFERENCE_ACCEPTED', note: 'close enough' }), '[-] a difference accepted on a line linked to nothing', 409, 'SETTLEMENT_NOTHING_TO_ACCEPT');
  expect(resolve(second.id, lineOf(open, 'RESERVE-HELD').id, { resolution: 'MATCHED_BY_HAND', targetId: e.payment.id }), '[-] a reserve is about no one payment', 409, 'SETTLEMENT_LINE_NOT_AN_EXCEPTION');
  expect(resolve(first.id, lineOf(firstFile, b.reference).id, { resolution: 'UNALLOCATED', note: 'second thoughts' }), '[abuse] a line in a payout already in the books', 409, 'SETTLEMENT_RECONCILED');

  expect(resolve(second.id, typed.id, { resolution: 'MATCHED_BY_HAND', targetId: e.payment.id }), '[+] the mistyped one is pointed at its payment', 200);
  expect(resolve(second.id, lineOf(open, d.reference).id, { resolution: 'DIFFERENCE_ACCEPTED', note: 'Terminal settled 1.00 short; asked Worldpay' }), '[+] the short one is accepted, with why', 200);
  expect(resolve(second.id, nobodys.id, { resolution: 'UNALLOCATED', note: 'Not ours: asked Worldpay' }), '[+] nobody\'s money goes to unallocated receipts', 200);
  expect(resolve(second.id, lineOf(open, a.reference).id, { resolution: 'UNALLOCATED', note: 'Paid twice: Worldpay will take it back' }), '[+] and so does the payment settled twice', 200);
  const last = resolve(second.id, lineOf(open, 'RESERVE-HELD').id, { resolution: 'UNALLOCATED', note: 'Rolling reserve, per the contract' });
  truthy('[+] with the last decision the payout is ready to sign off', last.status === 200 && data(last).batch.status === 'READY' && data(last).lines.length === 0, data(last) && data(last).batch);
  expect(resolve(second.id, nobodys.id, { resolution: 'UNALLOCATED', note: 'Not ours: Worldpay agrees' }), '[+] a decision may be changed until then', 200);
  expect(call('POST', `${SETTLEMENTS}/${second.id}/reconcile`, { token: cashier.token }), '[abuse] a cashier signs nothing off', 403);
  const signed = call('POST', `${SETTLEMENTS}/${second.id}/reconcile`, { token: owner });
  expect(signed, '[+] the owner signs it off', 200);
  truthy('[+] and it carries their name', data(signed).status === 'RECONCILED' && !!data(signed).reconciledBy, data(signed));
  expect(call('POST', `${SETTLEMENTS}/${second.id}/reconcile`, { token: owner }), '[abuse] it is signed off once', 409, 'SETTLEMENT_RECONCILED');

  const both = round(clean.net + messy.net);
  const heldBack = round(30 + a.total - 50 - 1);
  const settled = poll(90, () => {
    now = balances();
    return now.of(BANK) === both && now.of(CARD_CLEARING) === 0;
  });
  truthy('[+] the bank has both payouts and card clearing is empty again', settled >= 0 && now.balanced, { bank: now.of(BANK), both, clearing: now.of(CARD_CLEARING) });
  truthy('[+] what nobody could place sits in unallocated receipts for the books to explain', now.of(UNALLOCATED) === -heldBack, { unallocated: now.of(UNALLOCATED), heldBack });
  const kept = lineOf(data(batch(second.id)), `NOBODYS-${tag}`);
  truthy('[+] each decision keeps who made it and why', kept.resolution === 'UNALLOCATED' && kept.note === 'Not ours: Worldpay agrees' && !!kept.resolvedBy && !!kept.resolvedAt, kept);

  // ── the acquirers' own layouts ───────────────────────────────────────────────────────────────────
  const stripe =
    'automatic_payout_id,automatic_payout_effective_at_utc,balance_transaction_id,created_utc,currency,gross,fee,net,reporting_category,source_id,payment_intent_id,charge_id\n' +
    `po_${tag},${today} 00:00:00,txn_1,${today} 09:00:00,gbp,40.00,0.80,39.20,charge,ch_${tag},pi_${tag},ch_${tag}\n` +
    `po_${tag},${today} 00:00:00,txn_2,${today} 09:30:00,gbp,-2.00,0.00,-2.00,fee,fee_${tag},,\n` +
    `po_${tag},${today} 00:00:00,txn_3,${today} 10:00:00,gbp,-37.20,0.00,-37.20,payout,po_${tag},,\n`;
  const fromStripe = must(importFile({ provider: 'Stripe', format: 'stripe', content: stripe }), 201, 'a Stripe payout report');
  truthy('[+] Stripe\'s report names its payout, its date, its currency and its sum itself', fromStripe.reference === `po_${tag}` && fromStripe.payoutDate === today && fromStripe.currency === 'GBP' && num(fromStripe.declaredNet) === 37.2 && fromStripe.lineCount === 2, fromStripe);
  const adyen =
    'Company Account,Merchant Account,Psp Reference,Merchant Reference,Payment Method,Creation Date,TimeZone,Type,Modification Reference,Gross Currency,Gross Debit (GC),Gross Credit (GC),Exchange Rate,Net Currency,Net Debit (NC),Net Credit (NC),Commission (NC),Markup (NC),Scheme Fees (NC),Interchange (NC),Batch Number\n' +
    `Co,Shop,PSP${tag},ORDER-${tag},visa,${today} 09:00:00,UTC,Settled,,GBP,,40.00,1,GBP,,39.20,0.20,0.30,0.10,0.20,B${tag}\n` +
    `Co,Shop,,,,${today} 23:00:00,UTC,MerchantPayout,,GBP,,,,GBP,39.20,,,,,,B${tag}\n`;
  const fromAdyen = must(importFile({ provider: 'Adyen', format: 'ADYEN', content: adyen }), 201, 'an Adyen settlement details report');
  truthy('[+] and so does Adyen\'s, with the fee worked out from what was kept', fromAdyen.reference === `B${tag}` && num(fromAdyen.feeAmount) === 0.8 && num(fromAdyen.netAmount) === 39.2 && fromAdyen.status === 'EXCEPTIONS', fromAdyen);
  expect(importFile({ provider: 'Stripe', format: 'STRIPE', content: stripe.split(`po_${tag}`).join(`po_${tag}_eur`).split(',gbp,').join(',eur,') }), '[-] a payout in a currency the business does not trade in', 400, 'SETTLEMENT_CURRENCY_MIXED');
  const formats = data(call('GET', `${SETTLEMENTS}/formats`, { token: owner }));
  truthy('[+] the layouts read are listed, with the most lines a file may have', JSON.stringify(formats.formats) === '["ADYEN","STOREQL","STRIPE"]' && formats.maxLines === 20000, formats);

  // ── what cannot be imported ──────────────────────────────────────────────────────────────────────
  const one = [{ type: 'SALE', reference: `Z-${tag}`, gross: 10, fee: 0.1 }];
  const batchesBefore = (data(call('GET', `${SETTLEMENTS}?limit=100`, { token: owner })) || []).length;
  expect(importFile({ ...payout(`BAD-${tag}-1`, one).body, declaredNet: 99 }), '[-] a file that does not add up to what was paid', 400, 'SETTLEMENT_OUT_OF_BALANCE');
  expect(importFile({ ...payout(`BAD-${tag}-2`, one).body, format: 'WORLDLINE' }), '[-] a layout nobody reads', 400, 'SETTLEMENT_FORMAT_UNKNOWN');
  expect(importFile({ ...payout(`BAD-${tag}-3`, one).body, format: 'STRIPE' }), '[-] the wrong layout for the file', 400, 'SETTLEMENT_FILE_WRONG_LAYOUT');
  const flipped = importFile({ ...payout(`BAD-${tag}-4`, one).body, declaredNet: 5, content: `${HEADER}REFUND,R-${tag},,5.00,0.00,5.00\n` });
  expect(flipped, '[-] a refund written as money in is refused, not flipped', 400, 'SETTLEMENT_FILE_INVALID');
  truthy('[+] and the refusal says which line', /Line 2/.test(flipped.body), flipped.body);
  expect(importFile({ ...payout('', one).body }), '[-] a payout with no number', 400, 'SETTLEMENT_REFERENCE_MISSING');
  expect(importFile({ ...payout(`BAD-${tag}-5`, one).body, payoutDate: '2099-01-01' }), '[-] paid in the future', 400, 'SETTLEMENT_PAYOUT_DATE_INVALID');
  expect(importFile({ ...payout(`BAD-${tag}-6`, one).body, storeId: newId() }), '[-] for a store that is not the business\'s', 400, 'SETTLEMENT_STORE_UNKNOWN');
  expect(importFile({ provider: 'Worldpay', format: 'STOREQL' }), '[-] with no file at all', 400);
  expect(importFile({ ...payout(`BAD-${tag}-7`, one).body, content: `${HEADER}SALE,4111 1111 1111 1111,,10.00,0.10,9.90\n` }), '[abuse] a file carrying a full card number is stopped at the gateway', 400, 'CARD_DATA_NOT_ACCEPTED');
  const long = HEADER + Array.from({ length: 20001 }, (_, i) => `SALE,L${i}-${tag},,1.00,0.00,1.00`).join('\n');
  const tooLong = importFile({ ...payout(`BAD-${tag}-8`, one).body, content: long });
  expect(tooLong, '[abuse] a file past the line cap — past the usual body cap, within this route\'s', 400, 'SETTLEMENT_FILE_INVALID');
  truthy('[+] ...and says so', /more than 20000 lines/.test(tooLong.body), tooLong.body.slice(0, 200));
  expect(importFile({ ...payout(`BAD-${tag}-9`, one).body, content: 'x'.repeat(6200000) }), '[abuse] a body past the route\'s own cap', 413, 'PAYLOAD_TOO_LARGE');
  truthy('[+] nothing of any of them was kept', (data(call('GET', `${SETTLEMENTS}?limit=100`, { token: owner })) || []).length === batchesBefore);

  // ── whose it is ──────────────────────────────────────────────────────────────────────────────────
  expect(call('GET', SETTLEMENTS, { token: cashier.token }), '[abuse] a cashier reads no settlements', 403);
  expect(importFile(payout(`CASHIER-${tag}`, one).body, cashier.token), '[abuse] imports none', 403);
  expect(call('GET', `${SETTLEMENTS}/unsettled`, { token: cashier.token }), '[abuse] and does not see what is unpaid', 403);
  expect(resolve(second.id, nobodys.id, { resolution: 'UNALLOCATED', note: 'x' }, cashier.token), '[abuse] nor decides a line', 403);
  expect(call('GET', SETTLEMENTS), '[-] nor does anybody without a token', 401);
  expect(batch(first.id, '', rival.owner.token), '[abuse] another business cannot read this one\'s payout', 404, 'SETTLEMENT_NOT_FOUND');
  expect(call('POST', `${SETTLEMENTS}/${second.id}/reconcile`, { token: rival.owner.token }), '[abuse] or sign it off', 404, 'SETTLEMENT_NOT_FOUND');
  const theirs = must(importFile(payout(`WP-${tag}-1`, [{ type: 'SALE', reference: e.reference, gross: e.total, fee: 0.6 }]).body, rival.owner.token), 201, 'the rival imports a file naming this business\'s payment');
  truthy('[abuse] the same payout number is the rival\'s own, and this business\'s payment is not there to match', theirs.status === 'EXCEPTIONS' && theirs.openExceptions === 1, theirs);
  truthy('[+] the rival\'s list is only its own', (data(call('GET', SETTLEMENTS, { token: rival.owner.token })) || []).length === 1);

  // ── the register ─────────────────────────────────────────────────────────────────────────────────
  const page = call('GET', `${SETTLEMENTS}?limit=2`, { token: owner });
  const next = JSON.parse(page.body).meta.nextCursor;
  const rest = data(call('GET', `${SETTLEMENTS}?limit=2&after=${next}`, { token: owner }));
  truthy('[+] payouts page, the newest import first', data(page).length === 2 && !!next && rest.length === 2 && !data(page).some((r) => rest.some((o) => o.id === r.id)), rest.length);
  truthy('[+] and filter by how they stand', data(call('GET', `${SETTLEMENTS}?status=RECONCILED`, { token: owner })).length === 2 && data(call('GET', `${SETTLEMENTS}?status=EXCEPTIONS`, { token: owner })).length === 2);
  expect(call('GET', `${SETTLEMENTS}?status=PAID`, { token: owner }), '[-] by a status that exists', 400, 'SETTLEMENT_STATUS_UNKNOWN');
  const lines = call('GET', `${SETTLEMENTS}/${first.id}?limit=4`, { token: owner });
  truthy('[+] a payout\'s lines page in file order', data(lines).lines.length === 4 && data(lines).lines[0].lineNo === 1 && !!JSON.parse(lines.body).meta.nextCursor);

  // ── ten imports of one payout at once ────────────────────────────────────────────────────────────
  const f = cardSale(1, `F-${tag}`);
  const raced = payout(`WP-${tag}-RACE`, [{ type: 'SALE', reference: f.reference, gross: f.total, fee: 0.3 }]);
  const answers = http.batch(
    Array.from({ length: 10 }, (_, i) => ({
      method: 'POST',
      url: `${BASE}${SETTLEMENTS}`,
      body: JSON.stringify(raced.body),
      params: { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}`, 'Idempotency-Key': newId() } },
    }))
  ).map((r) => r.status);
  truthy('[abuse] ten imports of one payout at once make one batch', answers.filter((s) => s === 201).length === 1 && answers.filter((s) => s === 409).length === 9, answers);
  const final = poll(90, () => balances().of(BANK) === round(both + raced.net) && balances().of(CARD_CLEARING) === 0);
  truthy('[+] and the bank is told once', final >= 0, { bank: balances().of(BANK), expected: round(both + raced.net) });

  completed.add(1);
}
