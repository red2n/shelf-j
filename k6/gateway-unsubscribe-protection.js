// Gateway brute-force protection on the public opt-out endpoint: guessing unsubscribe tokens
// locks out the client IP the way guessing passwords does.
//
// POST /marketing/unsubscribe is necessarily unauthenticated (PECR reg.23 asks for a simple means
// of refusing; the token in the link is the whole capability). The token is 256 random bits, so it
// cannot be guessed — but an endpoint that would let someone try forever is still an endpoint that
// lets someone try forever. After shelfj.gateway.brute-force.max-failures wrong tokens (404s) from
// one address, the gateway answers 429 TOKEN_LOCKED without asking customer-svc, for
// shelfj.gateway.brute-force.block-minutes. A malformed request (400) is not a guess and is not
// counted; a right token is not affected until the lockout, and clears the counter.
//
// This locks out the machine running k6. k6/run.sh clears the lockout in the local Redis
// afterwards; against any other stack, the opt-out endpoint from this host stays blocked for 15
// minutes.
//
//   k6/run.sh gateway-unsubscribe-protection
import { ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, register, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

const MAX_FAILURES = Number(__ENV.BRUTE_FORCE_MAX_FAILURES || 5);
const BLOCK_MINUTES = Number(__ENV.BRUTE_FORCE_BLOCK_MINUTES || 15);

export function setup() {
  const tenant = onboardTenant('unsub', { country: 'GB', currency: 'GBP' });
  const shopper = register('unsub-shopper');
  const shop = { storefront: tenant.tenantId };
  const me = must(call('POST', '/api/customer-svc/customers/me', { token: shopper.token, ...shop }), 200, 'claim record');
  must(
    call('PUT', '/api/customer-svc/customers/me/marketing', {
      token: shopper.token,
      ...shop,
      body: { channels: [{ channel: 'EMAIL', granted: true }], notice: 'Offers' },
    }),
    200,
    'consent'
  );
  const allowance = must(
    call('GET', `/api/customer-svc/customers/${me.id}/marketing/allowance?channel=EMAIL`, { token: tenant.owner.token }),
    200,
    'allowance'
  );
  return { tenant, customerId: me.id, token: allowance.unsubscribeToken };
}

export default function ({ tenant, customerId, token }) {
  const unsub = (body) => call('POST', '/api/customer-svc/marketing/unsubscribe', { body });
  const owner = tenant.owner.token;

  expect(unsub({ token: '' }), 'a malformed request is a 400 and does not count as a guess', 400, 'VALIDATION_FAILED');
  for (let attempt = 1; attempt <= MAX_FAILURES; attempt++) {
    expect(unsub({ token: `guess-${attempt}-${Date.now()}` }), `wrong token ${attempt} of ${MAX_FAILURES} is a plain 404`, 404, 'UNSUBSCRIBE_TOKEN_INVALID');
  }
  const locked = unsub({ token: `guess-${Date.now()}` });
  expect(locked, 'the next guess is locked out at the gateway', 429, 'TOKEN_LOCKED');
  truthy('Retry-After tells the client how long', Number(locked.headers['Retry-After']) === BLOCK_MINUTES * 60, locked.headers);
  expect(unsub({ token }), 'even the right token is refused while locked', 429, 'TOKEN_LOCKED');
  truthy(
    'and the customer is still subscribed: the lockout stopped nothing',
    data(call('GET', `/api/customer-svc/customers/${customerId}/marketing/allowance?channel=EMAIL`, { token: owner })).allowed === true
  );
  expect(call('GET', '/api/tenant-svc/storefront/stores', { storefront: tenant.tenantId }), 'the rest of the storefront is unaffected', 200);
  expect(
    call('POST', '/api/iam-svc/auth/register', { body: { email: `after-token-lockout-${Date.now()}@k6.shelfj.test`, password: 'K6-Passw0rd!' } }),
    'registration is unaffected: the lockout is per path',
    201
  );
}
