# k6 suites

End-to-end tests that drive the running stack through the gateway, the way a browser, till or
integration would. Every call carries a real JWT; the gateway strips client-supplied
`X-Tenant-Id` / `X-User-Id` / `X-Roles`, so a script that sends those instead of a token tests
nothing.

**The flow guard is the heart of Shelf-J**, and it has two suites of its own (below). Run them after
any change to onboarding, tenant/store status, authorization, carts, orders or POS sessions.

## Running

Start the stack (`docker compose up -d --build`), then from the repo root:

```bash
k6/run.sh                             # flow guards, every functional suite, gateway checks
k6/run.sh flow-guard-runtime          # one suite (or several) by name
k6/run.sh --load                      # concurrent load: multi-tenant-retail, full-stack-simulation, rate limit
k6/db/validate_all.sh                 # after a run: what was written, and that every stored id is v7
```

`run.sh` reads the platform-admin login (`PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD`) and the
gateway's rate limit from the environment, `.env` and the running container. It waits until every
service is routable through the gateway (a container can be healthy before Consul lists it),
prints one line per suite, keeps full logs in `$K6_LOG_DIR` (a temp dir by default) and exits
non-zero if any suite fails. `BASE_URL` points it at another gateway (default
`http://localhost:8090`).

| Suite | What it proves | Checks |
|---|---|---|
| `flow-guard-comprehensive` | Onboarding → first sale in order: each step works once its prerequisites exist and is refused when too early, by the wrong role, or against another tenant (spoofed `X-Tenant-Id`, stranger on `/onboarding/*`, idempotent receipt and sale, currency, reservations) | 110 |
| `flow-guard-runtime` | Transact-or-not: suspending a tenant or closing a store stops the gateway storefront and checkout, staff login and refresh, POS sessions, carts and orders — for that tenant or store only — and reopening restores them; a store id from another tenant is never operational | 79 |
| `iam-crud` | Sign-up, login, refresh rotation and theft detection, logout, password change, account deletion, staff provisioning, POS sessions | 52 |
| `tenant-crud` | Tenant profile, stores (cursor pages), zones, delivery areas and routing, staff, inventory settings, storefront config, platform console | 78 |
| `product-crud` | Brands, categories, products, variants, UOM, item templates, revisions, containers, attribute groups, category sets, bulk import | 91 |
| `inventory-crud` | Receive, adjust, ABC, safety stock, cycle counts, lots, serials, transfers and the rest of inventory | 125 |
| `pricing-crud` | VAT rates, price lists and their lifecycle, resolution, promotions and quotes, overrides, VAT return | 67 |
| `order-crud` | Order lifecycle (pay, fulfil, return, cancel, void), receipts, POS log, fiscal receipts, special orders, parked sales, gift cards, layaways, the online shopper | 99 |
| `notification-crud` | Shortage alerts raised by real stock movements, send with event dedupe, notification log | 21 |
| `reporting-crud` | On-hand, supply/demand, movement stats and sales reports fed by a real receipt and a paid sale | 26 |
| `gateway-smoke-it` | Health, a public contract, sign-up, login and an authenticated call through the gateway | 7 |
| `privacy-flow` | The login-to-customer link, loyalty and the confirmation email on a web order, marketing consent and the opt-out link, the data export and erasure — each step with the wrong caller (guest, another shopper, a rival tenant's owner) and the wrong input beside the right one | 75 |
| `gateway-login-protection` | Five failed logins lock the account and the IP (429 `LOGIN_LOCKED`, `Retry-After`) | 13 |
| `gateway-unsubscribe-protection` | Five guessed opt-out tokens lock the IP out of the public unsubscribe endpoint (429 `TOKEN_LOCKED`); a malformed body is not a guess; the customer stays subscribed; the rest of the API is unaffected | 11 |
| `full-stack-simulation` *(load)* | One business browsing, reserving, selling at the till and restocking concurrently | ~1100 |
| `multi-tenant-retail` *(load)* | Two tenants (IN/INR, UK/GBP) across 28 concurrent scenarios; gates on zero isolation and security violations and a low error count | ~24000 |
| `gateway-rate-limit-stress` *(load)* | Exactly the configured per-IP budget is admitted in a 60 s window, then 429 `RATE_LIMITED`, never 5xx | ~30600 |

Every functional suite requires **all** checks to pass (`thresholds: { checks: ['rate==1.0'] }`).

Things to know:

- **Gateway suites lock this host out.** `gateway-login-protection` blocks logins and `gateway-unsubscribe-protection` blocks the opt-out endpoint from the k6
  machine for 15 minutes and `gateway-rate-limit-stress` spends its whole rate budget. `run.sh`
  clears those counters in the local `shelfj-redis` afterwards; run them directly with `k6 run`, or
  against a remote stack, and logins from this host stay blocked.
- **Status changes are asynchronous.** Suspensions and closures travel as `TenantStatusChanged` /
  `StoreStatusChanged` through the outbox and Kafka, and the gateway caches tenant status for 15 s.
  `flow-guard-runtime` polls each guard for up to 60 s and prints how long each took.
- **`multi-tenant-retail` shares fixtures between VUs.** Scenarios pick their tenant by VU parity
  and their variant by iteration, so two VUs occasionally act on the same batch or setting at once
  and a handful of read-back checks fail per run. Its thresholds gate on errors, isolation and
  security violations rather than on every check.
- **Purchase approval.** With `PURCHASE_APPROVAL_LIMITS` set, a currency without limits fails closed
  and nobody can approve its orders. The retail tenants trade in INR and GBP, so both need limits.

## Writing a suite

Put the file in `k6/` as `<area>-crud.js` (or `flow-guard-*.js`), add its name to `FUNCTIONAL` or
`LOAD` in `run.sh`, and build it from `lib/shelfj.js`:

```js
import { ALL_CHECKS_PASS, call, data, expect, onboardTenant, register, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

export function setup() {
  // setup() runs once; what it returns is passed to the default function.
  return { tenant: onboardTenant('widgets', { stores: 1 }), rival: onboardTenant('widgets-rival'), shopper: register('widgets-shopper') };
}

export default function ({ tenant, rival, shopper }) {
  const t = tenant.owner.token;
  const made = call('POST', '/api/product-svc/admin/brands', { token: t, body: { name: 'Acme' } });
  expect(made, '[+] create brand', 201);
  expect(call('POST', '/api/product-svc/admin/brands', { token: t, body: {} }), '[-] name required', 400, 'VALIDATION_FAILED');
  expect(call('GET', `/api/product-svc/admin/brands/${data(made).id}`, { token: rival.owner.token }), "[-] a rival cannot read it", 404);
  expect(call('POST', '/api/product-svc/admin/brands', { token: shopper.token, body: { name: 'x' } }), '[-] a customer cannot', 403);
  expect(call('POST', '/api/product-svc/admin/brands', { body: { name: 'x' } }), '[-] no token', 401);
}
```

`lib/shelfj.js` provides:

| Helper | Does |
|---|---|
| `call(method, path, { body, token, storefront, idem, headers })` | One request through the gateway. `storefront: tenantId` sets `X-Storefront-Tenant` for guest and shopper paths; `idem: true` sends a fresh `Idempotency-Key` (required to place an order), or pass the key to replay one. Ids in the path are folded out of the metric name. |
| `data(res)`, `errorCode(res)`, `nextCursor(res)` | Read the `{ data, error, meta }` envelope. |
| `expect(res, label, status, code?)` | A check on the status (one or a list) and, optionally, the error code. Logs the response when it fails, so a red check says why. |
| `truthy(label, value, detail?)` | A check on a computed value; logs `detail` when it fails. |
| `must(res, status, what)` | For prerequisites: throws (failing setup or the iteration) unless the status matches. |
| `poll(seconds, fn)` | Retries `fn` once a second until it returns truthy; returns the seconds taken or -1. Use it for anything that crosses Kafka. |
| `register(label)`, `login(user)`, `signInUntil(user, claimsPredicate)` | Identities. A sign-up is a CUSTOMER; `signInUntil` logs in until the token carries what an event granted (tenant, role). |
| `onboardTenant(label, { country, currency, stores })` | Owner, tenant and stores, with the owner's token already carrying the tenant and OWNER. |
| `staffUser(tenant, role, storeIds)` | A staff login assigned at those stores, signed in with the role. |
| `platformAdmin()` | The bootstrap platform admin (env `PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD`). |
| `sellableVariant(tenant, name)`, `priceVariants(tenant, variantIds, price)`, `receive(tenant, storeId, variantId, qty)` | A sellable product with one variant, an active price list in the tenant's currency, a stock receipt. |
| `setTenantStatus(admin, tenantId, status)`, `setStoreStatus(tenant, storeId, status)` | The flow-guard switches. |
| `newId()`, `newKey(prefix)`, `uniq()` | A UUIDv7 for any id a script sends that gets stored (Shelf-J keeps only v7 — `validate_all.sh` fails on anything else), an idempotency key, a run-unique suffix. |

Conventions the existing suites follow:

- **Positive and negative.** Label checks `[+]` and `[-]`. For each capability prove the happy
  path, the validation failure (400 with the code), the wrong role (403), no token (401) and another
  tenant (404, never 403 — a 403 would confirm the id exists).
- **Exact expectations.** Pin the status and the error code the service documents. A check that
  accepts "any 4xx" or "200 or 404" passes whatever happens. When a check fails, find out whether
  the service or the expectation is wrong before changing either.
- **Isolation.** Use a second tenant from `onboardTenant` for cross-tenant checks, never a made-up
  id: a random id is not found for everyone and proves nothing.
- **Asynchrony.** Anything fed by events (role grants, status projections, reports, alerts) is
  polled with `poll` or `signInUntil`, never read once after a `sleep`.
- **Run data is disposable.** Every suite creates its own tenants with unique names, so suites can
  run in any order and repeatedly against the same database.

## Flow guard: adding a guarded surface

A new place where money or stock moves must be added to both flow-guard suites:

1. The service checks `TenantStatusRepository.isActive(tenantId)` and
   `StoreStatusRepository.isActive(tenantId, storeId)` (common-service projections fed by
   `BaseTenantStatusChangedConsumer` / `BaseStoreStatusChangedConsumer`) and answers 409
   `TENANT_NOT_OPERATIONAL` / `STORE_NOT_OPERATIONAL`.
2. In `flow-guard-runtime.js`, add a probe function for it and call it in each phase: open at
   baseline, refused within `PROPAGATION` after suspension and after closure, open for the other
   tenant and store throughout, open again after reopening.
3. In `flow-guard-comprehensive.js`, add the step where it belongs in onboarding-to-sale, with its
   too-early, wrong-role and other-tenant refusals.
