// The e-invoicing transport seam (07.13, 18.9), through the gateway, over the simulated network —
// the only provider a stack with no access-point contract can choose, and the suite says so: the
// real access point is deployed, offered, and refused as unconfigured. The owner chooses Peppol; a
// till sale to a registered buyer is invoiced and the document is delivered without anyone asking,
// with the network's reference kept; a buyer the network does not know is refused for good, and sent
// again by hand only to be refused again; a buyer whose access point is slow is taken and asked after
// until delivered; a buyer with no electronic address has nowhere to receive. The outbox lists and
// pages every attempt. Refused: a network or provider that does not exist, a provider without
// credentials, Peppol for a business with no address, an account too long, sending what was
// delivered, sending with no network chosen, a status that does not exist, a cashier choosing or
// reading. Abuse: ten sends at once for one document sending once, another tenant's reads and sends,
// a path that is not an id.
//
//   k6/run.sh einvoice-transport
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  poll,
  sellingTenant,
  truthy,
} from './lib/shelfj.js';

// Added on the last line only, so a flow that stopped part-way fails instead of passing.
const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  batch: 10,
  batchPerHost: 10,
  setupTimeout: '5m',
};

const O = '/api/order-svc';
const TRANSPORT = `${O}/admin/einvoicing/transport`;
const BUYER_VAT = 'GB555555555';

// A GLN (GS1 GTIN-13) with its check digit, as Peppol's 0088 scheme requires.
const gln = (first12) => {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(first12[11 - i]) * (i % 2 === 0 ? 3 : 1);
  return `${first12}${(10 - (sum % 10)) % 10}`;
};

const identity = (tenant, body) => {
  const token = tenant.owner.token;
  const profile = must(call('GET', '/api/tenant-svc/admin/tenant', { token }), 200, 'tenant profile');
  return must(
    call('PUT', '/api/tenant-svc/admin/tenant', { token, body: { businessName: profile.name, legalName: profile.legalName, ...body } }),
    200,
    'tenant identity'
  );
};

const customer = (tenant, label, endpointId) => {
  const token = tenant.owner.token;
  const run = Date.now().toString(36);
  const c = must(
    call('POST', '/api/customer-svc/customers', { token, body: { email: `${label}-${run}@k6.shelfj.test`, firstName: label, lastName: 'Buyer' } }),
    201,
    `customer ${label}`
  );
  must(call('POST', `/api/customer-svc/customers/${c.id}/addresses`, { token, body: { type: 'BILLING', line1: '2 Mill Lane', city: 'Leeds', country: 'GB', pincode: 'LS1 4AB' } }), 201, `address ${label}`);
  const vat = { customerId: c.id, vatRegistered: true, vatNumber: BUYER_VAT, countryCode: 'GB', legalName: `${label} Ltd` };
  if (endpointId) Object.assign(vat, { einvoiceScheme: '9932', einvoiceId: endpointId });
  must(call('POST', '/api/pricing-svc/customer-vat-status', { token, body: vat }), [200, 201], `vat status ${label}`);
  return c.id;
};

export function setup() {
  const gb = sellingTenant('einvoice-tx', { price: '10.00' });
  // A GLN of this suite's own: the outbound suite's business keeps its own address.
  identity(gb.tenant, { vatNumber: 'GB123456789', einvoiceScheme: '0088', einvoiceId: gln(`5790000${String(Date.now() % 100000).padStart(5, '0')}`) });
  const noaddr = sellingTenant('einvoice-tx-noaddr', { price: '10.00' });
  identity(noaddr.tenant, { vatNumber: 'GB987654321' });
  const cafe = customer(gb.tenant, 'cafe', BUYER_VAT);
  const nobody = customer(gb.tenant, 'nobody', `${BUYER_VAT}REJECT`);
  const slow = customer(gb.tenant, 'slow', `${BUYER_VAT}LATER`);
  const offline = customer(gb.tenant, 'offline', null);
  return { gb, noaddr, cafe, nobody, slow, offline };
}

export default function ({ gb, noaddr, cafe, nobody, slow, offline }) {
  const t = gb.tenant.owner.token;
  const auth = (token) => ({ Authorization: `Bearer ${token}` });
  const settings = (token = t) => call('GET', TRANSPORT, { token });
  const choose = (body, token = t) => call('PUT', TRANSPORT, { token, body });
  const send = (id, token = t) => call('POST', `${O}/admin/sales-invoices/${id}/transmissions`, { token, body: {} });
  const attempts = (id, token = t) => call('GET', `${O}/admin/sales-invoices/${id}/transmissions`, { token });
  const outbox = (query = '', token = t) => call('GET', `${O}/admin/einvoicing/transmissions${query}`, { token });
  const documentOf = (id) => data(call('GET', `${O}/admin/sales-invoices/${id}`, { token: t }));

  // A till sale, paid for in cash, invoiced on payment; the invoice once it exists.
  const invoiced = (customerId) => {
    const order = must(
      call('POST', `${O}/orders`, { token: t, idem: true, body: { storeId: gb.store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', customerId, items: [{ variantId: gb.variantId, qty: 1 }] } }),
      201,
      'till sale'
    );
    must(call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: gb.store.id, currency: 'GBP' } }), [200, 201], 'cash payment');
    let found;
    poll(60, () => (found = (data(call('GET', `${O}/admin/orders/${order.id}/invoices`, { token: t })) || []).find((d) => d.kind === 'INVOICE')));
    truthy(`[+] the sale ${order.id.slice(0, 8)} is invoiced`, found, order.id);
    return found;
  };
  // The document's newest transmission once it is in one of the states; the worker runs every 15s.
  const transmissionOf = (id, states, seconds = 60) => {
    let found;
    poll(seconds, () => {
      const tr = documentOf(id).transmission;
      found = tr && states.includes(tr.status) ? tr : undefined;
      return found;
    });
    return found;
  };

  // ── what is offered ──────────────────────────────────────────────────────────────────────────────
  const offered = settings();
  expect(offered, '[+] the business starts sending nowhere, and is told what this deployment offers', 200);
  const s0 = data(offered);
  truthy('[+] ...no network yet, four to choose from, the simulated provider on each', s0.network === 'NONE' && s0.networks.join() === 'PEPPOL,FR_PDP,KSEF,IRP' && ['PEPPOL', 'FR_PDP', 'KSEF', 'IRP'].every((n) => (s0.providers[n] || []).includes('SIMULATED')), s0);
  truthy('[+] ...the Peppol access point deployed but not choosable: this stack holds no credentials', (s0.providers.PEPPOL || []).includes('ACCESS_POINT') && !(s0.available.PEPPOL || []).includes('ACCESS_POINT'), s0);
  truthy("[+] ...France's platform and India's portal likewise, and the portal takes the business's own credential", (s0.providers.FR_PDP || []).includes('PDP') && !(s0.available.FR_PDP || []).includes('PDP') && (s0.providers.IRP || []).includes('NIC') && !(s0.available.IRP || []).includes('NIC') && (s0.needingSecret.IRP || []).join() === 'NIC' && s0.hasSecret === false, s0);
  truthy('[+] ...and the business\'s own electronic address', typeof s0.senderAddress === 'string' && s0.senderAddress.startsWith('0088:'), s0.senderAddress);

  // ── refusals ─────────────────────────────────────────────────────────────────────────────────────
  expect(settings(gb.cashier.token), '[-] a cashier does not read where invoices leave', 403);
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED' }, gb.cashier.token), '[-] nor chooses it', 403);
  expect(choose({ network: 'FAX', provider: 'SIMULATED' }), '[-] a network that does not exist is refused', 400, 'EINVOICE_NETWORK_UNKNOWN');
  expect(choose({ network: 'PEPPOL' }), '[-] a network needs a provider', 400, 'EINVOICE_PROVIDER_REQUIRED');
  expect(choose({ network: 'KSEF', provider: 'ACCESS_POINT' }), '[-] a provider that does not serve the network is refused', 400, 'EINVOICE_PROVIDER_UNKNOWN');
  expect(choose({ network: 'PEPPOL', provider: 'ACCESS_POINT' }), '[-] the access point cannot be chosen without its credentials', 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'FR_PDP', provider: 'PDP' }), "[-] nor France's platform", 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'IRP', provider: 'NIC', providerAccount: 'user', providerSecret: 'pass' }), "[-] nor India's portal", 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED', providerSecret: 'a-password' }), '[-] a credential cannot be kept on a deployment with no secrets key', 409, 'EINVOICE_SECRETS_KEY_MISSING');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED' }, noaddr.tenant.owner.token), '[-] Peppol needs the business\'s own electronic address', 409, 'EINVOICE_SENDER_ADDRESS_MISSING');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED', providerAccount: 'x'.repeat(121) }), '[-] an account name too long is refused', 400);
  expect(choose({ network: 'KSEF', provider: 'SIMULATED' }, noaddr.tenant.owner.token), '[+] KSeF needs no address: the network takes the sender\'s own', 200);

  const chosen = choose({ network: 'PEPPOL', provider: 'SIMULATED', providerAccount: 'LE-K6' });
  expect(chosen, '[+] the owner chooses Peppol over the simulated provider', 200);
  truthy('[+] ...and reads it back', data(chosen).network === 'PEPPOL' && data(chosen).provider === 'SIMULATED' && data(chosen).providerAccount === 'LE-K6', data(chosen));

  // ── delivered, refused, deferred, nowhere to go ──────────────────────────────────────────────────
  const inv = invoiced(cafe);
  const delivered = transmissionOf(inv.id, ['ACCEPTED']);
  truthy('[+] the invoice is delivered over the network without anyone asking', delivered, documentOf(inv.id));
  truthy('[+] ...with the network\'s reference, to the buyer\'s address, by the chosen provider', delivered && delivered.providerRef.startsWith('SIM-') && delivered.receiver === `9932:${BUYER_VAT}` && delivered.provider === 'SIMULATED' && delivered.sentAt, delivered);
  expect(attempts(inv.id), '[+] the attempts are read by document', 200);
  truthy('[+] ...one, delivered', data(attempts(inv.id)).length === 1, data(attempts(inv.id)));
  expect(send(inv.id), '[-] a delivered document is not sent again', 409, 'EINVOICE_ALREADY_SENT');

  const refused = invoiced(nobody);
  const rejected = transmissionOf(refused.id, ['REJECTED']);
  truthy('[+] a buyer the network does not know is refused, for good, with the reason', rejected && rejected.detail.includes('knows no participant'), rejected);
  const again = send(refused.id);
  expect(again, '[+] sent again by hand, the network answers at once', 200);
  truthy('[+] ...refused again: the address is in the document', data(again).status === 'REJECTED', data(again));
  truthy('[+] ...and both attempts are kept', data(attempts(refused.id)).length === 2, data(attempts(refused.id)));

  const deferred = invoiced(slow);
  const late = transmissionOf(deferred.id, ['ACCEPTED'], 110);
  truthy('[+] a buyer whose access point is slow is taken, asked after, and delivered', late && late.attempts >= 2, documentOf(deferred.id));

  const nowhere = invoiced(offline);
  expect(send(nowhere.id), '[-] a buyer with no electronic address has nowhere to receive', 409, 'EINVOICE_RECEIVER_ADDRESS_MISSING');
  truthy('[-] ...and nothing was queued for it', !documentOf(nowhere.id).transmission, documentOf(nowhere.id));

  // ── the outbox ───────────────────────────────────────────────────────────────────────────────────
  const accepted = outbox('?status=accepted&limit=1');
  expect(accepted, '[+] the outbox lists every attempt, filtered and paged', 200);
  truthy('[+] ...one delivered on the first page, and a cursor to the rest', data(accepted).length === 1 && data(accepted)[0].status === 'ACCEPTED' && accepted.json('meta.nextCursor'), accepted.body.slice(0, 300));
  const rest = outbox(`?limit=100&after=${accepted.json('meta.nextCursor')}`);
  truthy('[+] ...which reads', rest.status === 200 && data(rest).length >= 2, data(rest) && data(rest).length);
  expect(outbox('?status=LOST'), '[-] a status that does not exist is refused', 400, 'EINVOICE_TRANSMISSION_STATUS_UNKNOWN');
  expect(outbox('?after=not-a-cursor'), '[-] a cursor that is not an attempt is refused', 400);

  // ── abuse ────────────────────────────────────────────────────────────────────────────────────────
  const rival = gb.rival.owner.token;
  truthy('[abuse] another tenant\'s outbox is empty', (data(outbox('', rival)) || []).length === 0, 'rival outbox');
  expect(attempts(inv.id, rival), '[abuse] another tenant does not see the attempts', 404);
  expect(send(inv.id, rival), '[abuse] nor sends the document', 404);
  const path = call('GET', `${O}/admin/sales-invoices/not-an-id/transmissions`, { token: t });
  truthy('[abuse] a path that is not an id is refused, not an error', path.status === 400 || path.status === 404, path.status);
  const before = data(attempts(refused.id)).length;
  const atOnce = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${O}/admin/sales-invoices/${refused.id}/transmissions`, '{}', { headers: { ...auth(t), 'Content-Type': 'application/json' }, tags: { name: 'POST /admin/sales-invoices/{id}/transmissions' } }]));
  const sent = atOnce.filter((r) => r.status === 200).length;
  const told = atOnce.filter((r) => r.status === 409).length;
  truthy('[abuse] ten sends at once for one document send it once, and tell the other nine', sent === 1 && told === 9 && data(attempts(refused.id)).length === before + 1, { sent, told, statuses: atOnce.map((r) => r.status) });

  // ── no network ───────────────────────────────────────────────────────────────────────────────────
  expect(choose({ network: 'NONE' }), '[+] the owner can choose to send nowhere again', 200);
  const kept = invoiced(cafe);
  expect(send(kept.id), '[-] with no network chosen a document is issued and kept, never sent', 409, 'EINVOICE_TRANSPORT_NOT_SET');
  truthy('[-] ...and nothing was queued', !documentOf(kept.id).transmission, documentOf(kept.id));

  completed.add(1);
}
