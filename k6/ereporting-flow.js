// E-reporting: the second limb of France's reform (18.9), through the gateway.
//
// A business that issues every invoice correctly and reports nothing is still in breach. The invoice
// limb was built; this is the other one — the transactions no invoice covers, reported to the
// administration through the same platform. A shop selling to the public issues no e-invoices at all
// and still owes a report three times a month.
//
// What only a live stack proves is the seam between two services: tenant-svc derives the calendar
// (two returns at a new ten-day frequency, each due ten days after its period) and holds what was
// filed, while order-svc builds the data, transmits it and answers with the payload's digest — which
// is then recorded as the filing, tying the bytes to the calendar without tenant-svc holding a copy.
// The aggregation itself is asserted in order-svc's integration tests, where a sale can be backdated
// into a period that has ended; nothing here can backdate, so what is reported here is a period with
// nothing in it — which is itself a case worth having, because an empty period is transmitted rather
// than skipped.
//
// Refused: a period that has not ended, one longer than a month, an unknown return, a business the
// duty does not bind, a network that carries invoices and not reports, a business with no network at
// all, a period already reported, a cashier, and an anonymous caller.
//
//   k6/run.sh ereporting-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, sellingTenant, truthy, uniq } from './lib/shelfj.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const O = '/api/order-svc/admin/ereporting';
const T = '/api/tenant-svc/admin/tenant/statutory-returns';

const TX = 'EREPORTING_TX_FR';
const PAY = 'EREPORTING_PAY_FR';

/** A day, as the API writes one. */
function isoDay(offsetDays = 0) {
  return new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);
}

/** The ten-day period a day falls in, derived the same way the calendar derives it. */
function decadalPeriod(day) {
  const d = new Date(day + 'T00:00:00Z');
  const dom = d.getUTCDate();
  const startDom = dom <= 10 ? 1 : dom <= 20 ? 11 : 21;
  const start = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), startDom));
  const end =
    startDom === 21
      ? new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1))
      : new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), startDom + 10));
  return { start: start.toISOString().slice(0, 10), end: end.toISOString().slice(0, 10) };
}

/**
 * Records how the business's documents name it: its VAT number, and an electronic address.
 *
 * <p>The address is there so the business can be switched to Peppol further down — which is refused
 * without one, and refusing for the wrong reason would prove nothing about e-reporting.
 */
function identity(tenant, vatNumber, scheme, id) {
  const token = tenant.owner.token;
  const profile = must(call('GET', '/api/tenant-svc/admin/tenant', { token }), 200, 'tenant profile');
  must(
    call('PUT', '/api/tenant-svc/admin/tenant', {
      token,
      body: {
        businessName: profile.name,
        legalName: profile.legalName,
        vatNumber,
        einvoiceScheme: scheme,
        einvoiceId: id,
      },
    }),
    200,
    'tenant identity',
  );
}

export function setup() {
  const fr = sellingTenant('ereport-fr', { country: 'FR', currency: 'EUR', price: '10.00' });
  identity(fr.tenant, 'FR32123456789', '0009', '32123456789001');
  const gb = sellingTenant('ereport-gb', { price: '10.00' });
  identity(gb.tenant, 'GB123456789', '0088', '5790000435975');
  return { fr, gb };
}

export default function ({ fr, gb }) {
  const t = fr.tenant.owner.token;
  const tag = uniq().toUpperCase().slice(0, 8);
  const post = (path, body, token) => call('POST', path, { token: token || t, body });
  const get = (path, token) => call('GET', path, { token: token || t });
  const sends = (tenant, network, provider) =>
    must(
      call('PUT', '/api/order-svc/admin/einvoicing/transport', {
        token: tenant.owner.token,
        body: { network, provider, providerAccount: '123456789' },
      }),
      200,
      `${network} settings`,
    );

  // ── the calendar: two returns, three periods a month ─────────────────────────────────────────────
  // The calendar answers an object — asOf, obligations, outstanding — not a bare list.
  const obligations = (data(get(T)) || {}).obligations || [];
  const tx = obligations.find((o) => o.returnCode === TX);
  const pay = obligations.find((o) => o.returnCode === PAY);
  truthy('[+] a French business owes both e-reporting streams', !!tx && !!pay, obligations.map((o) => o.returnCode));
  truthy('[+] ...three times a month, not monthly', tx && tx.frequency === 'DECADAL', tx);
  truthy(
    '[+] ...and the period on the calendar is the ten-day one the law means',
    tx && tx.periodStart && decadalPeriod(tx.periodStart).start === tx.periodStart,
    tx,
  );
  truthy(
    '[+] ...due ten days after the period it reports',
    tx && new Date(tx.dueOn) - new Date(tx.periodEnd) === 10 * 86400000,
    tx,
  );
  const gbObligations = (data(get(T, gb.tenant.owner.token)) || {}).obligations || [];
  truthy(
    '[-] a British business owes neither: a duty is asked of the country, not assumed',
    !gbObligations.some((o) => o.returnCode === TX || o.returnCode === PAY),
    gbObligations.map((o) => o.returnCode),
  );

  // ── reporting a period ───────────────────────────────────────────────────────────────────────────
  sends(fr.tenant, 'FR_PDP', 'SIMULATED');

  // The period before last: ended, and nothing in it — which is reported rather than skipped.
  const period = decadalPeriod(isoDay(-15));
  const preview = data(get(`${O}/preview?return=${TX}&from=${period.start}&to=${period.end}`));
  truthy('[+] a period with nothing in it says so', preview && preview.nothingToReport === true, preview);
  truthy('[+] ...and the currency is the business\'s own', preview && preview.currency === 'EUR', preview);

  const sent = data(post(`${O}/submissions`, { returnCode: TX, periodStart: period.start, periodEnd: period.end }));
  truthy('[+] the platform takes the report and answers as the network did', sent && sent.status === 'ACCEPTED', sent);
  truthy('[+] ...with the payload\'s digest, which is what the filing names', sent && sent.payloadDigest && sent.payloadDigest.length === 44, sent);
  const document = call('GET', `${O}/submissions/${sent.id}/document`, { token: t });
  expect(document, '[+] the document downloads as it was transmitted', 200);
  truthy(
    '[+] ...and an empty period says néant in words rather than by omission',
    String(document.body).includes('<Neant>true</Neant>'),
    String(document.body).slice(0, 200),
  );

  // The two services meet here: the digest goes on the filing, and the calendar says filed.
  expect(
    post(`${T}/${TX}/filings`, {
      periodStart: period.start,
      provider: 'SIMULATED',
      reference: sent.providerRef || `SIM-${tag}`,
      payloadDigest: sent.payloadDigest,
    }),
    '[+] the filing is recorded against the calendar, naming the bytes',
    200,
  );
  const filed = ((data(get(T)) || {}).obligations || []).find(
    (o) => o.returnCode === TX && o.periodStart === period.start,
  );
  truthy('[+] ...and the calendar now says filed', filed && filed.state === 'FILED', filed);

  // ── the refusals ─────────────────────────────────────────────────────────────────────────────────
  expect(
    post(`${O}/submissions`, { returnCode: TX, periodStart: period.start, periodEnd: period.end }),
    '[-] a period already reported is refused; a correction is the way',
    409,
    'EREPORTING_ALREADY_SUBMITTED',
  );
  const corrected = data(
    post(`${O}/submissions`, {
      returnCode: TX,
      periodStart: period.start,
      periodEnd: period.end,
      corrects: sent.id,
    }),
  );
  truthy('[+] a correction supersedes its predecessor, and both stay', corrected && corrected.supersedes === sent.id, corrected);
  truthy(
    '[+] ...the first now says what replaced it',
    data(get(`${O}/submissions/${sent.id}`)).supersededBy === corrected.id,
  );

  const current = decadalPeriod(isoDay(0));
  expect(
    post(`${O}/submissions`, { returnCode: TX, periodStart: current.start, periodEnd: current.end }),
    '[-] a period that has not ended cannot be reported',
    400,
    'EREPORTING_PERIOD_INVALID',
  );
  expect(
    post(`${O}/submissions`, { returnCode: TX, periodStart: isoDay(-200), periodEnd: isoDay(-1) }),
    '[-] nor a mistyped window that would sweep months into one filing',
    400,
    'EREPORTING_PERIOD_INVALID',
  );
  expect(
    post(`${O}/submissions`, { returnCode: 'EREPORTING_VAT_XX', periodStart: period.start, periodEnd: period.end }),
    '[-] an unknown return is refused before anything is read',
    400,
    'EREPORTING_RETURN_UNKNOWN',
  );
  expect(
    post(`${O}/submissions`, { returnCode: PAY, periodStart: period.start, periodEnd: period.end }, gb.tenant.owner.token),
    '[-] a business the duty does not bind reports nothing',
    409,
    'EREPORTING_NOT_DUE',
  );

  // A network that carries invoices and not reports: silently accepting one would leave a business
  // believing it had reported.
  sends(fr.tenant, 'PEPPOL', 'SIMULATED');
  expect(
    post(`${O}/submissions`, { returnCode: PAY, periodStart: period.start, periodEnd: period.end }),
    '[-] Peppol carries invoices, not reports',
    409,
    'EREPORTING_NETWORK_CANNOT_REPORT',
  );
  must(
    call('PUT', '/api/order-svc/admin/einvoicing/transport', { token: t, body: { network: 'NONE' } }),
    200,
    'no network',
  );
  expect(
    post(`${O}/submissions`, { returnCode: PAY, periodStart: period.start, periodEnd: period.end }),
    '[-] and with no network at all there is nowhere to report',
    409,
    'EREPORTING_TRANSPORT_NOT_SET',
  );

  // ── who may ──────────────────────────────────────────────────────────────────────────────────────
  expect(get(`${O}/submissions`, fr.cashier.token), '[-] a cashier does not read the reports', 403);
  expect(
    post(`${O}/submissions`, { returnCode: TX, periodStart: period.start, periodEnd: period.end }, fr.cashier.token),
    '[-] nor file one',
    403,
  );
  expect(call('GET', `${O}/submissions`), '[-] an anonymous caller is refused', 401);
  expect(get(`${O}/submissions/${sent.id}`, gb.tenant.owner.token), '[-] another business cannot read this one\'s report', 404);

  completed.add(1);
}
