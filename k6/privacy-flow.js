// The privacy tranche, end to end through the gateway, with the wrong caller and the wrong input
// at every step: the login-to-customer link (SJ-D44), marketing consent and the opt-out link
// (PECR reg.22/23), the data export (UK GDPR art.20) and erasure (art.17).
//
// Positive and negative cases sit side by side on purpose. Every "the shopper can" is followed by
// "and nobody else can", because the tests that were green while SJ-D44 was open were all
// positive.
//
//   k6/run.sh privacy-flow
import { group } from 'k6';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  register,
  sellableVariant,
  truthy,
} from './lib/shelfj.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: ALL_CHECKS_PASS,
};

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

function order(tenant, storeId, variantId, shopper, extra = {}) {
  return call('POST', '/api/order-svc/orders', {
    token: shopper.token,
    storefront: tenant.tenantId,
    idem: true,
    body: {
      storeId,
      channel: 'ONLINE',
      fulfilmentType: 'DELIVERY',
      deliveryLine1: '12 High Street',
      deliveryCity: 'London',
      deliveryPostalCode: 'EC1A 1BB',
      deliveryRecipientName: 'Chris Carter',
      deliveryRecipientPhone: '07700900123',
      contactPhone: '07700900123',
      items: [{ variantId, qty: 1, unitPrice: '25.00' }],
      ...extra,
    },
  });
}

export function setup() {
  const tenant = onboardTenant('privacy', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('privacy-rival', { country: 'GB', currency: 'GBP' });
  const { variantId } = sellableVariant(tenant, 'Privacy Tea');
  priceVariants(tenant, [variantId]);
  must(receive(tenant, tenant.stores[0].id, variantId, 50), [200, 201], 'receive stock');
  return { tenant, rival, variantId };
}

export default function ({ tenant, rival, variantId }) {
  const shop = { storefront: tenant.tenantId };
  const owner = tenant.owner.token;
  const shopper = register('privacy-shopper');
  const other = register('privacy-other');
  const ctx = {};

  group('1 the link', () => {
    expect(call('GET', '/api/customer-svc/customers/me', { token: shopper.token, ...shop }), 'before buying: the shop holds no record', 404, 'CUSTOMER_NOT_FOUND');
    expect(call('POST', '/api/customer-svc/customers/me', { ...shop }), 'a guest cannot claim a record', 401);
    expect(call('GET', '/api/customer-svc/customers/me', { token: shopper.token }), 'no storefront tenant: no tenant to hold a record', [401, 403]);

    const placed = order(tenant, tenant.stores[0].id, variantId, shopper);
    expect(placed, 'a signed-in shopper places a delivery order', 201);
    ctx.orderId = data(placed).id;
    truthy('the order carries the login', data(placed).loginId === shopper.userId, data(placed));
    truthy('and the customer id the link resolved', typeof data(placed).customerId === 'string' && data(placed).customerId !== shopper.userId, data(placed));
    ctx.customerId = data(placed).customerId;

    const mine = call('GET', '/api/customer-svc/customers/me', { token: shopper.token, ...shop });
    expect(mine, 'the shopper now has a record here', 200);
    truthy('it is the one on the order', data(mine).id === ctx.customerId, data(mine));
    truthy('with the email from the token', data(mine).email === shopper.email.toLowerCase(), data(mine));
    const again = call('POST', '/api/customer-svc/customers/me', { token: shopper.token, ...shop });
    expect(again, 'claiming again finds the same record', 200);
    truthy('not a second one', data(again).id === ctx.customerId, data(again));

    expect(call('GET', '/api/customer-svc/customers/me', { token: other.token, ...shop }), 'another shopper has no record here', 404);
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}`, { token: other.token, ...shop }), "another shopper cannot read the record by id", [403, 404]);
    expect(call('GET', `/api/order-svc/orders/${ctx.orderId}`, { token: other.token, ...shop }), "another shopper cannot read the order", 404, 'ORDER_NOT_FOUND');
    expect(call('GET', `/api/order-svc/orders/${ctx.orderId}`, { token: shopper.token, ...shop }), 'the shopper reads their own order', 200);
    const history = call('GET', '/api/order-svc/orders/mine', { token: shopper.token, ...shop });
    expect(history, 'and it is in their history', 200);
    truthy('history is filtered on the login', (data(history) || []).some((o) => o.id === ctx.orderId), data(history));
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}`, { token: rival.owner.token }), "a rival tenant's owner cannot read the record", 404);
  });

  group('2 loyalty and the confirmation email', () => {
    expect(call('POST', `/api/order-svc/orders/${ctx.orderId}/confirm`, { token: owner, body: {} }), 'the shop confirms the order', 200);
    const took = poll(60, () => {
      const acct = call('GET', `/api/customer-svc/customers/${ctx.customerId}/loyalty`, { token: owner });
      return acct.status === 200 && Number(data(acct).pointsBalance) > 0;
    });
    truthy(`loyalty accrued on a web order (${took}s)`, took >= 0);
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}/loyalty`, { token: other.token, ...shop }), "another shopper cannot read the loyalty account", [403, 404]);
  });

  group('3 consent', () => {
    const prefs = '/api/customer-svc/customers/me/marketing';
    expect(call('GET', prefs, { token: shopper.token, ...shop }), 'no preferences yet: an empty list, not an error', 200);
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner }), 'silence is not consent: the allowance is no', 200);
    truthy('and says why', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner })).allowed === false);

    const marketing = (customerId) =>
      call('POST', '/api/notification-svc/notifications/send', {
        token: owner,
        body: { recipient: shopper.email, subject: `Offers ${Date.now()}`, body: 'Half price week', category: 'MARKETING', customerId },
      });
    expect(marketing(ctx.customerId), 'a marketing send with no consent is refused', 409, 'MARKETING_CONSENT_MISSING');
    expect(marketing(undefined), 'a marketing send naming nobody is refused', 409, 'MARKETING_CONSENT_MISSING');
    expect(marketing(UNKNOWN), 'a marketing send to an unknown customer is refused', 409, 'MARKETING_CONSENT_MISSING');
    expect(
      call('POST', '/api/notification-svc/notifications/send', {
        token: owner,
        body: { recipient: shopper.email, subject: 'Your order', body: 'On its way', type: 'ORDER_UPDATE', customerId: ctx.customerId },
      }),
      'a transactional message needs no consent',
      202
    );

    expect(call('PUT', prefs, { token: shopper.token, ...shop, body: { channels: [{ channel: 'FAX', granted: true }] } }), 'an unknown channel is refused', 400, 'MARKETING_CHANNEL_UNKNOWN');
    expect(call('PUT', prefs, { token: shopper.token, ...shop, body: { channels: [] } }), 'no channels is refused', 400);
    expect(call('PUT', prefs, { token: shopper.token, ...shop, body: { channels: [{ channel: 'EMAIL', granted: true, basis: 'BECAUSE' }] } }), 'an invented basis is refused', 400, 'MARKETING_BASIS_UNKNOWN');
    expect(call('PUT', prefs, { ...shop, body: { channels: [{ channel: 'EMAIL', granted: true }] } }), 'a guest cannot consent for anyone', 401);
    expect(call('PUT', `/api/customer-svc/customers/${ctx.customerId}/marketing`, { token: other.token, ...shop, body: { channels: [{ channel: 'EMAIL', granted: true }] } }), "another shopper cannot consent on the shopper's behalf", 403);

    const set = call('PUT', prefs, { token: shopper.token, ...shop, body: { channels: [{ channel: 'EMAIL', granted: true }], notice: 'Email me about offers' } });
    expect(set, 'the shopper consents to email', 200);
    truthy('and it is recorded as consent', (data(set) || []).some((p) => p.channel === 'EMAIL' && p.granted && p.basis === 'CONSENT'), data(set));

    const allowance = call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner });
    expect(allowance, 'the allowance is now yes', 200);
    truthy('with an opt-out token for the message', typeof data(allowance).unsubscribeToken === 'string' && data(allowance).unsubscribeToken.length > 30, data(allowance));
    truthy('SMS is still no: consent is per channel', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=SMS`, { token: owner })).allowed === false);
    ctx.unsubscribeToken = data(allowance).unsubscribeToken;

    expect(marketing(ctx.customerId), 'the marketing send now goes', 202);
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: rival.owner.token }), "a rival tenant's owner gets no allowance for our customer", 200);
    truthy('and it is no', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: rival.owner.token })).allowed === false);
  });

  group('4 the opt-out link', () => {
    const unsub = (body) => call('POST', '/api/customer-svc/marketing/unsubscribe', { body });
    expect(unsub({ token: '' }), 'a blank token is a validation error', 400, 'VALIDATION_FAILED');
    expect(unsub({}), 'no token at all is a validation error', 400, 'VALIDATION_FAILED');
    expect(unsub({ token: ctx.unsubscribeToken, channel: 'FAX' }), 'a real token with an unknown channel stops nothing', 400, 'MARKETING_CHANNEL_UNKNOWN');
    truthy('email consent survived the bad request', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner })).allowed === true);

    const out = unsub({ token: ctx.unsubscribeToken });
    expect(out, 'one click, no sign-in, stops it', 200);
    truthy('every channel, because that is what the link means', data(out).channelsStopped === 4, data(out));
    truthy('email is now no', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner })).allowed === false);
    expect(unsub({ token: ctx.unsubscribeToken }), 'a second click is not an error: an objection does not expire', 200);
    expect(
      call('POST', '/api/notification-svc/notifications/send', {
        token: owner,
        body: { recipient: shopper.email, subject: 'Offers again', body: 'More offers', category: 'MARKETING', customerId: ctx.customerId },
      }),
      'the next marketing send is refused',
      409,
      'MARKETING_CONSENT_MISSING'
    );
  });

  group('5 the export', () => {
    const exp = call('GET', '/api/customer-svc/customers/me/export', { token: shopper.token, ...shop });
    expect(exp, 'the shopper downloads their data', 200);
    const d = data(exp);
    truthy('both ids are on it', d.subject && d.subject.loginId === shopper.userId && d.subject.customerId === ctx.customerId, d.subject);
    truthy('the order is in it, with its address', (d.orders || []).some((o) => o.id === ctx.orderId && o.deliveryRecipientName === 'Chris Carter'), (d.orders || []).map((o) => o.id));
    truthy('the loyalty ledger is in it', Array.isArray(d.loyaltyLedger) && d.loyaltyLedger.length >= 1, d.loyaltyLedger);
    truthy('the consent trail is in it, with the wording', (d.marketingConsentLog || []).some((e) => e.notice === 'Email me about offers'), d.marketingConsentLog);
    truthy('and the opt-out too', (d.marketingConsentLog || []).some((e) => e.source === 'UNSUBSCRIBE_LINK'), d.marketingConsentLog);

    const theirs = call('GET', '/api/customer-svc/customers/me/export', { token: other.token, ...shop });
    expect(theirs, 'another shopper gets their own export', 200);
    truthy("with none of the shopper's orders in it", !(data(theirs).orders || []).some((o) => o.id === ctx.orderId), data(theirs).orders);
    expect(call('GET', '/api/customer-svc/customers/me/export', { ...shop }), 'a guest gets nothing', 401);
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}/export`, { token: other.token, ...shop }), "another shopper cannot export by id", 403);
    expect(call('GET', `/api/customer-svc/customers/${UNKNOWN}/export`, { token: owner }), 'staff export of an unknown customer is 404', 404, 'CUSTOMER_NOT_FOUND');
    expect(call('GET', `/api/customer-svc/customers/${ctx.customerId}/export`, { token: rival.owner.token }), "a rival tenant's owner cannot export our customer", 404);
    const staff = call('GET', `/api/customer-svc/customers/${ctx.customerId}/export`, { token: owner });
    expect(staff, 'staff export for a request that came by phone', 200);
    truthy('is the same document', (data(staff).orders || []).length === (d.orders || []).length);
    expect(call('GET', `/api/order-svc/orders/export?login=${shopper.userId}`, { token: shopper.token, ...shop }), 'the raw order export is staff-only', 403);
  });

  group('6 erasure', () => {
    expect(call('POST', `/api/order-svc/orders/${ctx.orderId}/fulfil`, { token: owner, body: {} }), 'the order is delivered', 200);
    expect(call('DELETE', `/api/customer-svc/customers/${ctx.customerId}`, { token: other.token, ...shop }), 'a shopper cannot erase anyone', 403);
    expect(call('DELETE', `/api/customer-svc/customers/${ctx.customerId}`, { token: rival.owner.token }), "a rival tenant's owner cannot erase our customer", 404);
    expect(call('DELETE', `/api/customer-svc/customers/${ctx.customerId}`, { token: owner }), 'the shop erases the customer', 204);

    const took = poll(60, () => {
      const o = call('GET', `/api/order-svc/orders/${ctx.orderId}`, { token: owner });
      return o.status === 200 && !data(o).deliveryRecipientName && !data(o).deliveryPostalCode && !data(o).contactPhone;
    });
    truthy(`the delivered web order lost its name, postcode and phone (${took}s)`, took >= 0);
    const redacted = data(call('GET', `/api/order-svc/orders/${ctx.orderId}`, { token: owner }));
    truthy('and kept its total', Number(redacted.total) > 0, redacted);
    truthy('and its login, for the shopper who is not gone', redacted.loginId === shopper.userId, redacted);
    truthy('marketing is refused for an erased record', data(call('GET', `/api/customer-svc/customers/${ctx.customerId}/marketing/allowance?channel=EMAIL`, { token: owner })).allowed === false);
    expect(call('PUT', `/api/customer-svc/customers/${ctx.customerId}/marketing`, { token: owner, body: { channels: [{ channel: 'EMAIL', granted: true }] } }), 'and cannot be re-granted after erasure', 409, 'CUSTOMER_ANONYMIZED');
    expect(call('DELETE', `/api/customer-svc/customers/${ctx.customerId}`, { token: owner }), 'erasing twice changes nothing and publishes nothing', [204, 409]);

    const after = call('GET', '/api/customer-svc/customers/me/export', { token: shopper.token, ...shop });
    expect(after, 'the shopper can still export after the shop erased its record', 200);
    truthy('the order is there, redacted', (data(after).orders || []).some((o) => o.id === ctx.orderId && !o.deliveryRecipientName), (data(after).orders || []).map((o) => o.deliveryRecipientName));
  });
}
