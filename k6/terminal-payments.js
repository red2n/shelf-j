// EMV terminals and pinpads (07.16), through the gateway.
//
// A CARD tender used to be recorded because a cashier said so. Nothing asked a terminal whether the
// card was approved, and nothing kept what a card receipt has to carry — the scheme, the four digits
// a receipt may print, the authorisation code, the application the card ran, how it was read and how
// the cardholder was verified.
//
// The check this suite exists for is the double charge: the SAME Idempotency-Key must never reach the
// terminal twice. That is how a real customer gets charged twice — the screen does not change, the
// cashier presses again — so the attempt is claimed before the device is asked anything.
//
// The second is the timeout, which is not a decline. A decline took nothing and a failure never
// started, but a timeout means the card MAY have been charged: no tender is recorded, nothing is
// retried, and the attempt keeps its reference so it can be found in the acquirer's settlement file.
//
// The simulator picks its outcome from the amount's minor units, the acquirers' own convention:
// .01 declines, .02 is cancelled, .03 times out, .04 fails. Everything else is approved.
//
//   k6/run.sh terminal-payments
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, newId, newKey, sellingTenant, truthy, uniq } from './lib/shelfj.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const ADMIN = '/api/payment-svc/admin/payments/terminals';
const TERM = '/api/payment-svc/payments/terminal';

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  const shop = sellingTenant(`emv-${tag}`);
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier.token;
  const store = shop.store.id;

  // ── the register ────────────────────────────────────────────────────────────────────────────────
  const vendors = data(call('GET', `${ADMIN}/vendors`, { token: owner })) || [];
  truthy('[+] only makes this deployment can talk to are offered', vendors.includes('SIMULATED'), vendors);

  const registered = call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED', serial: `SN-${tag}` } });
  expect(registered, '[+] a business registers a card machine for a store', 201);
  const terminal = data(registered);
  truthy('[+] it is on the counter and says it is simulated', terminal.status === 'ACTIVE' && terminal.vendor === 'SIMULATED', terminal);

  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED' } }),
    '[-] two active machines cannot share a label in one store', 409, 'TERMINAL_ALREADY_REGISTERED');
  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Other ${tag}`, vendor: 'MY_OWN_PINPAD' } }),
    '[-] nor a make the platform cannot talk to', 400, 'TERMINAL_VENDOR_UNKNOWN');
  // Belt to the gateway's braces: nothing card-shaped may arrive by any route, including a label.
  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: '4242 4242 4242 4242', vendor: 'SIMULATED' } }),
    '[abuse] and a card number is not accepted as a machine’s name', 400, 'TERMINAL_CARD_DATA_NOT_ACCEPTED');

  // ── taking a card ───────────────────────────────────────────────────────────────────────────────
  // Each attempt is against its own order, so one outcome cannot be mistaken for another's. The id is
  // a real UUIDv7 from the lib: the platform keeps only v7 ids and its own audit counts anything else
  // as a defect, so a hand-made one would be a defect this suite introduced.
  const sale = (amount, key, token) => call('POST', TERM, {
    token: token || cashier,
    idem: key,
    body: { terminalId: terminal.id, orderId: newId(), amount, currency: shop.tenant.currency },
  });

  const key = newKey('press');
  const first = sale('12.50', key);
  expect(first, '[+] the machine answers, and the card is approved', 201);
  const approved = data(first);
  truthy('[+] with what a card receipt has to carry', !!approved.scheme && !!approved.panLast4 && !!approved.authCode && !!approved.entryMode && !!approved.aid, approved);
  truthy('[+] and the receipt line already assembled, so every printer agrees', /\*\*\*\*/.test(approved.receiptLine || ''), approved.receiptLine);
  truthy('[abuse] four digits of the card and no more of it anywhere', (approved.panLast4 || '').length === 4 && !JSON.stringify(approved).includes('4242424242424242'), approved.panLast4);

  // THE check. A second press with the same key must find the first attempt.
  const again = call('POST', TERM, { token: cashier, idem: key, body: { terminalId: terminal.id, orderId: approved.orderId, amount: '12.50', currency: shop.tenant.currency } });
  expect(again, '[+] a second press with the same key is answered, not refused', 201);
  truthy('[+] and it is the SAME attempt, not a second charge on the card', data(again).id === approved.id, { first: approved.id, again: data(again).id });
  truthy('[+] with the same authorisation code', data(again).authCode === approved.authCode, { a: approved.authCode, b: data(again).authCode });
  const history = data(call('GET', `${TERM}/by-order/${approved.orderId}`, { token: cashier })) || [];
  truthy('[+] one attempt on the record for one press, however many times it was pressed', history.length === 1, history.length);

  // ── the outcomes that are not approvals ────────────────────────────────────────────────────────
  const declined = data(sale('9.01', newKey('press')));
  truthy('[-] a declined card is settled and says why, in the machine’s own words', declined.state === 'DECLINED' && !!declined.outcomeDetail, declined);
  truthy('[-] and nothing is printed for it, because nothing was taken', !declined.receiptLine && !declined.paymentId, declined);

  const cancelled = data(sale('9.02', newKey('press')));
  truthy('[-] a cancellation at the machine is not an approval', cancelled.state === 'CANCELLED', cancelled);

  const timedOut = data(sale('9.03', newKey('press')));
  truthy('[abuse] a timeout is its own state, never a decline and never an approval', timedOut.state === 'TIMED_OUT', timedOut);
  truthy('[abuse] no tender: the platform does not claim money it cannot prove it took', !timedOut.paymentId, timedOut);
  // The reference is the only way to find this attempt in the acquirer's settlement file, which is the
  // only way to learn whether the card was really charged.
  truthy('[+] but it keeps its reference, which is how the money is traced', !!timedOut.providerRef, timedOut);

  const failed = data(sale('9.04', newKey('press')));
  truthy('[-] a fault is settled too — nothing is left waiting for ever', failed.state === 'FAILED' && !!failed.settledAt, failed);

  // ── putting money back ────────────────────────────────────────────────────────────────────────
  const back = call('POST', `${TERM}/${approved.id}/refunds`, { token: owner, idem: newKey('refund'), body: { amount: '5.00' } });
  expect(back, '[+] money goes back on the card that paid', 201);
  truthy('[+] linked to the attempt it puts back, never to a card presented again', data(back).refundOf === approved.id && data(back).kind === 'REFUND', data(back));
  expect(call('POST', `${TERM}/${approved.id}/refunds`, { token: owner, body: { amount: '999.00' } }),
    '[-] never more than the card took', 409, 'TERMINAL_REFUND_TOO_LARGE');
  expect(call('POST', `${TERM}/${declined.id}/refunds`, { token: owner, body: { amount: '1.00' } }),
    '[-] and nothing at all against an attempt that took nothing', 409, 'TERMINAL_NOT_APPROVED');
  expect(call('POST', `${TERM}/${approved.id}/refunds`, { token: cashier, body: { amount: '1.00' } }),
    '[abuse] a cashier does not put money back on a card', 403);

  // ── what the amount may be ────────────────────────────────────────────────────────────────────
  for (const [amount, why] of [['0.00', 'nothing'], ['-5.00', 'a negative'], ['1.005', 'a third decimal place']]) {
    expect(call('POST', TERM, { token: cashier, body: { terminalId: terminal.id, orderId: approved.orderId, amount, currency: shop.tenant.currency } }),
      `[-] ${why} is refused rather than rounded`, 400);
  }

  // ── retiring one ──────────────────────────────────────────────────────────────────────────────
  expect(call('POST', `${ADMIN}/${terminal.id}/retire`, { token: owner, body: { reason: 'screen cracked' } }), '[+] a machine is retired with a reason', 200);
  expect(sale('7.00', newKey('press')), '[-] a retired machine takes no more cards', 409, 'TERMINAL_RETIRED');
  const all = data(call('GET', ADMIN, { token: owner })) || [];
  truthy('[+] and is kept rather than deleted, because payments point at it', all.some((t) => t.id === terminal.id && t.status === 'RETIRED'), all.map((t) => t.status));
  // Which frees the label for the machine that replaces it — what happens when a pinpad is swapped.
  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED' } }),
    '[+] its name is free for the machine that replaces it', 201);

  // ── whose machines these are ───────────────────────────────────────────────────────────────────
  expect(call('GET', ADMIN, { token: cashier }), '[abuse] a cashier does not read the register of machines', 403);
  expect(call('GET', ADMIN, { token: shop.rival.owner.token }), '[+] another business reads its own register', 200);
  truthy('[abuse] which holds none of this one’s machines', ((data(call('GET', ADMIN, { token: shop.rival.owner.token })) || []).length === 0), 'empty');
  expect(call('POST', TERM, { token: shop.rival.owner.token, body: { terminalId: terminal.id, orderId: approved.orderId, amount: '5.00', currency: shop.tenant.currency } }),
    '[abuse] and it cannot take a card on this one’s machine by naming its id', 404, 'TERMINAL_NOT_FOUND');
  expect(call('GET', `${TERM}/by-order/${approved.orderId}`), '[-] nor does anybody without a token', 401);

  completed.add(1);
}
