# FIND.md — Deep-dive code audit (backend services)

**Scope:** All `platform/`, `services/`, and `shared/` Java modules (244 files, ~41k LOC). Frontends (`frontends/`) excluded per request.
**Date:** 2026-06-16 · **Branch:** `flow-guard`
**Method:** manual read of the security boundary (gateway + iam), the shared infra (JDBC/Kafka/outbox), the commerce core (order/payment/cart/inventory), plus targeted sweeps for SQL-injection, tenant-isolation gaps, unbounded caches, thread leaks, idempotency, and secret handling.

## Overall assessment

The codebase is, on the whole, **defensively well-built**. The things that usually go wrong in a multi-tenant commerce platform are mostly handled correctly here:

- ✅ **JWT trust boundary is sound** — the gateway strips client-supplied identity headers and re-stamps them from the verified token; downstream services read tenant only from `TenantContext`.
- ✅ **No SQL injection** — every query uses `PreparedStatement` with bound parameters; the few dynamic-SQL builders (`ProductRepository`, `OrderRepository.listOrders`) only append static predicate fragments, never user data.
- ✅ **Money/stock concurrency is correct** — gift-card redeem, payment refund caps, and inventory FIFO deduction all use `SELECT … FOR UPDATE` row locks inside a transaction.
- ✅ **Idempotency** — the critical additive event handlers (inventory stock deduction, goods-received, reporting projections) dedupe via `processed_events` inside the write transaction; the rest are naturally idempotent (state-guarded transitions / upserts).
- ✅ **No error/stack/SQL leakage** — `GenericExceptionMapper` returns a sanitized 500 and logs server-side only.
- ✅ **Secret hygiene** — `.env` is git-ignored (only `.env.example` is tracked), docker ports bind to `127.0.0.1`, and the JWT secret uses compose's `:?` required syntax.

The findings below are the exceptions. The two **HIGH** items are genuine financial-integrity holes in `order-svc` and should be fixed before any real-money deployment. Everything else is medium/low.

---

## Severity summary

| # | Severity | Area | Issue |
|---|----------|------|-------|
| 1 | 🔴 HIGH | order-svc | Client-controlled `discountAmount`/`taxAmount` bypass server-side pricing |
| 2 | 🔴 HIGH | order-svc | Returns/refunds have no quantity cap → over-refund & repeat-refund |
| 3 | 🟠 MED-HIGH | order-svc / config | Server-side pricing enforcement ships **OFF** by default |
| 4 | 🟠 MED | cart-svc | Missing object-level authorization (cart IDOR) |
| 5 | 🟠 MED | gateway | `TenantStatusGate` cache is unbounded (memory leak + amplification) |
| 6 | 🟠 MED | deploy | Weak default platform-admin password in auto-bootstrap |
| 7 | 🟠 MED | shared | Kafka poison-pill blocks its partition forever (no DLQ / max-retry) |
| 8 | 🟡 LOW | payment-svc | Online payment capture is trust-based; amount not validated at capture |
| 9 | 🟡 LOW | shared | Outbox drain not concurrency-safe across instances (duplicate publishes) |
| 10 | 🟡 LOW | iam-svc | `changePassword` reads raw `X-User-Id` header instead of `TenantContext` |
| 11 | 🟡 LOW | gateway | Throttle-cap eviction can drop a live (legit) limiter entry |

---

## ✅ 1. FIXED — Client controls discount & tax even when server-side pricing is enforced

**Where:** [services/order-svc/.../service/OrderService.java](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java#L117-L123)

```java
BigDecimal tax  = req.taxAmount()      != null ? req.taxAmount()      : BigDecimal.ZERO;
BigDecimal disc = req.discountAmount() != null ? req.discountAmount() : BigDecimal.ZERO;
if (disc.compareTo(subtotal) > 0) throw ...ORDER_DISCOUNT_EXCEEDS_SUBTOTAL...
BigDecimal total = subtotal.add(tax).subtract(disc);
```

**Problem:** Gap #63 was closed for **unit price** (resolved from pricing-svc when `pricingEnforce` is on), but `taxAmount` and `discountAmount` still come **straight from the request body** on every channel. The only guard is `disc ≤ subtotal`.

**Impact (financial):** A storefront customer placing an `ONLINE` order needs no staff role (`isOpenMutation("/orders")` + `JwtAuthFilter.isStorefrontCustomer`). They can submit `discountAmount = subtotal`, `taxAmount = 0` and drive `total` to ~zero, then self-confirm via the online payment path (Finding #8). Result: near-free goods. This defeats the entire point of server-side pricing.

**Fix:** For non-staff / `ONLINE` orders, do not trust client discount/tax — derive them server-side:
- `taxAmount` from pricing-svc / VAT rules for the resolved lines.
- `discountAmount` only from validated promotions resolved server-side (pricing-svc already owns `/promotions`).
- Reject any non-zero client `discountAmount` unless the caller holds a staff role (manual POS discounts are legitimate; online self-discounts are not).

```java
boolean staff = ctx.hasRole("CASHIER") || ctx.hasRole("MANAGER")
             || ctx.hasRole("OWNER")  || ctx.hasRole("PLATFORM_ADMIN");
if (!staff && disc.signum() != 0)
    throw ApiException.forbidden("ORDER_DISCOUNT_NOT_ALLOWED", "discounts are applied server-side");
if (enforcePricing) {
    tax = pricing.resolveTax(tenantId, items, storeId, req.channel());   // server-derived
    // disc = pricing.resolvePromotions(...);                            // server-derived
}
```

---

## ✅ 2. FIXED — Returns/refunds have no quantity cap (over-refund + repeat-refund)

**Where:** [OrderService.createReturn](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java#L265-L321) → [OrderRepository.createReturn](services/order-svc/src/main/java/com/shelfj/order/repo/OrderRepository.java#L224-L262)

```java
BigDecimal refundAmt = matched.unitPrice().multiply(ri.qty());  // ri.qty() is unbounded
totalRefund = totalRefund.add(refundAmt);
```

`ReturnItemRequest.qty` is validated `@Positive` only — there is **no check that `ri.qty()` ≤ the quantity originally purchased**, and **no accounting of previously-returned quantity**. The repo inserts the `returns`/`return_items` rows and emits the refund event unconditionally.

**Impact (financial):** A staff user (returns require a management role) can return qty `1000` against an order line that bought `1`, or call the endpoint repeatedly, each time producing a full refund event (`OrderReturned` → payment refund / loyalty credit downstream). This is a classic insider-fraud / reconciliation hole.

**Fix:** Validate per line inside the same transaction that creates the return:

```java
BigDecimal alreadyReturned = repo.sumReturnedQty(tenantId, orderId, variantId); // SUM(return_items.qty)
if (ri.qty().add(alreadyReturned).compareTo(matched.qty()) > 0)
    throw ApiException.conflict("RETURN_QTY_EXCEEDS_PURCHASED",
        "cannot return more than was purchased (and not yet returned)");
```

Run the `SUM` + insert atomically (and ideally lock the order’s return rows) so two concurrent returns can’t jointly exceed the purchased quantity.

---

## ✅ 3. FIXED — Server-side pricing enforcement ships OFF by default

**Where:** [.env](.env) → `SHELFJ_ORDER_PRICING_ENFORCE=false`; default in `order-svc` `ServiceConfig#pricingEnforce`.

When `pricingEnforce=false`, [OrderService.placeOrder](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java#L95-L102) **trusts the client-supplied `unitPrice` entirely** — any caller sets any price. The README correctly says "production MUST be true", but the committed default (and the local `.env`) is `false`, so the insecure mode is the path of least resistance.

**Impact:** If this value is ever carried into a non-dev environment, the storefront becomes "name your own price." Combined with #1, the whole order total is attacker-controlled.

**Fix applied:** The Java-level default (`ServiceConfig#pricingEnforce` `defaultValue`) was already `true` (fail-closed); the insecure `false` only comes from the docker-compose/`.env` override needed because `pricing-svc` ships with no seeded price catalogue for local dev. Flipping the compose default outright would break every local order, so instead `ServiceConfig` now has a `@PostConstruct` (`warnIfPricingEnforcementDisabled`) that logs a `WARNING` on every order-svc boot whenever `pricingEnforce=false`, calling out explicitly that client-supplied price/tax/discount are being trusted and that this must never be true outside local dev. `.env` and `.env.example` comments were also strengthened to spell this out next to the variable.

---

## ✅ 4. FIXED — Cart access has no object-level authorization (IDOR)

**Where:** [CartService](services/cart-svc/src/main/java/com/shelfj/cart/service/CartService.java#L82-L204) — `addItem`, `updateItemQty`, `removeItem`, `viewCart`/`resolveCart`.

Carts were located purely by `cartId` (or `sessionId`) scoped to tenant:

```java
Cart cart = repo.findById(tenantId, cartId)
    .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", ...));
// never checked cart.customerId() == ctx.userId()  (or that the session belongs to the caller)
```

The cart's `customerId`/`sessionId` was **never compared to the authenticated caller**. Any authenticated user who knew/guessed another user's `cartId` (or guest `sessionId`) in the same tenant could read, add to, change quantities in, and delete items from that cart.

> Exposure was limited because the gateway whitelist (`JwtAuthFilter`) does not yet route guest/customer storefront traffic to `cart-svc` — so this was reachable mainly by staff tokens. The moment cart paths are added to the storefront whitelist (required for the online cart to function), this would have become directly exploitable.

**Fix applied:** Added `CartService.requireOwnership(cart, ctx, suppliedSessionId)`, called from `addItem`, `updateItemQty`, `removeItem`, and the explicit-`cartId` branch of `resolveCart` (used by `viewCart`). Staff roles (`CASHIER`/`STOREKEEPER`/`MANAGER`/`OWNER`/`PLATFORM_ADMIN`) bypass the check (assisted shopping on any cart in their tenant); an authenticated customer's cart must have `cart.customerId().equals(ctx.userId())`; a guest cart additionally requires the caller to supply the `sessionId` the cart was created with — `cartId` alone is no longer sufficient. `AddItemRequest`/`UpdateItemQtyRequest` gained an optional `sessionId` field, and `DELETE /cart/items/{itemId}` gained a `session` query param, to carry that proof through. All mismatches return `404 CART_NOT_FOUND` (not `403`) so existence of another tenant's/customer's cart is never confirmed. Covered by new unit tests in `CartServiceTest` (`addItem_blockedForCustomerWhoDoesNotOwnTheCart`, `addItem_allowedForStaffOnAnyCustomersCart`, `addItem_guestCartRequiresMatchingSessionId`).

---

## ✅ 5. FIXED — `TenantStatusGate` cache is unbounded (memory leak + amplification)

**Where:** [platform/gateway/.../filters/TenantStatusGate.java](platform/gateway/src/main/java/com/shelfj/gateway/filters/TenantStatusGate.java#L33-L45)

```java
private final Map<String, Cached> cache = new ConcurrentHashMap<>();
...
cache.put(tenantId, new Cached(active, now + TTL_MILLIS));   // no size cap, no eviction
```

Unlike `RateLimitFilter` and `BruteForceProtectionService` (which both cap at `MAX_BUCKETS = 10_000` and evict stale entries), this cache had **no bound and no expired-entry eviction** — expired entries lingered until overwritten by the same key. The key is `X-Storefront-Tenant`, which on guest paths is **client-controlled**. A caller (or botnet) rotating that header across many distinct values could grow the map without limit (gateway OOM), and each new value also triggers a `GET /storefront/active` to tenant-svc (request amplification against tenant-svc).

**Fix applied:** `TenantStatusGate` now mirrors the bounded pattern already used by `RateLimitFilter`: a `MAX_ENTRIES = 10_000` hard cap, eviction of expired entries first on a cap hit, and an arbitrary-entry drop as the last resort if eviction frees nothing (so the cap is a real bound under sustained churn, not just a best-effort hint). Covered by new `TenantStatusGateTest` (`cacheNeverGrowsPastTheHardCapUnderTenantIdChurn`, plus existing-behavior regression tests for TTL caching and fail-open).

---

## ✅ 6. FIXED — Weak default platform-admin password in auto-bootstrap

**Where:** [docker-compose.yml](docker-compose.yml#L728-L733) — `ADMIN_PASSWORD: ${PLATFORM_ADMIN_PASSWORD:-Admin1234!}`, which the bootstrap job POSTs to `/api/iam-svc/bootstrap/admin`.

The first `PLATFORM_ADMIN` was auto-created with a **known default password** (`Admin1234!`) unless `PLATFORM_ADMIN_PASSWORD` was set. The bootstrap endpoint is one-shot (rejects if an admin exists), so the account — full platform superuser — was created with a publicly-known credential on any deploy that forgot to override it.

**Fix applied:** `docker-compose.yml` now uses the same required-variable syntax as `SHELFJ_JWT_SECRET`/`SHELFJ_CONFIG_TOKEN` — `ADMIN_PASSWORD: ${PLATFORM_ADMIN_PASSWORD:?PLATFORM_ADMIN_PASSWORD must be set - see .env.example}` — so the stack refuses to boot the bootstrap job without an explicit password. `.env.example` documents `PLATFORM_ADMIN_EMAIL`/`PLATFORM_ADMIN_PASSWORD` with a generation hint (`openssl rand -base64 24`) and no default value; `.env` was given a freshly generated random password. Verified with `docker compose config`.

---

## ✅ 7. FIXED — Kafka poison-pill blocks its partition indefinitely

**Where:** [shared/common-service/.../KafkaEventLoop.java](shared/common-service/src/main/java/com/shelfj/service/KafkaEventLoop.java)

The retry model was "throw = redeliver, seek back to the failed offset." A record whose handler **always** threw (genuinely malformed payload that isn't caught, a referenced row that never appears, a persistent bug) was re-polled every 2s **forever**, and because the loop rewinds to the first failure per partition, **all later records on that partition were blocked** behind it.

**Impact:** One bad event could silently stall an entire partition's event processing (stock updates, order confirmations) with no alert and no escape.

**Fix applied:** `KafkaEventLoop` now tracks the retry count per `(partition, offset)` in a small `attempts` map (resetting when the stuck offset moves on). A record is still rewound and retried as before while `count < MAX_ATTEMPTS` (5). Once exhausted, the loop logs at `ERROR`, publishes the record to a `<topic>.DLT` topic via a lazily-created `KafkaProducer` (most loops never need one), clears the attempt entry, and — critically — does **not** add the offset to the `rewind` map, so `commitSync()` advances the partition past the poison record instead of re-blocking it. `close()` also closes the dead-letter producer if one was created. Covered by new unit tests in `KafkaEventLoopTest` (attempt counting, per-offset reset, per-partition independence) exercising the retry-tracking logic directly via reflection, since the class has no DI seam and constructing it doesn't touch the network.

---

## ✅ 8. FIXED — Online payment capture is trust-based; amount not validated at capture

**Where:** [services/payment-svc/.../service/PaymentService.java](services/payment-svc/src/main/java/com/shelfj/payment/service/PaymentService.java)

`recordTender` accepted `req.amount()` and `req.orderId()` with no verification that the order existed, belonged to the caller, or that the amount matched the order total — it just recorded a `CAPTURED` tender and emitted `PaymentCaptured`. There is no payment-service-provider (PSP) integration, so an online payment was effectively self-attested. The only downstream guard was `OrderService.handlePaymentCaptured` requiring `amount ≥ total`.

**Impact:** On the `/payments/online` path (reachable by a customer token, no staff role required), a caller could self-confirm an order without a real charge. This is partly inherent to "no PSP yet," but the missing order-existence / ownership / amount checks made it worse and paired badly with #1.

**Fix applied:** Added `OrderClient` (`services/payment-svc/.../client/OrderClient.java`), a Consul-discovered sync client for order-svc's `GET /orders/{id}` (mirrors `order-svc`'s existing `PricingClient`: `@Retry` + `@CircuitBreaker`, 503 on unreachable). `PaymentService` now has two entry points instead of one: `recordTender` (unchanged — the staff/POS path, trust boundary is the caller's role) and a new `recordOnlinePayment` used only by `/payments/online`, which calls order-svc first and rejects (404 `PAYMENT_ORDER_NOT_FOUND`, not leaking which check failed) unless the order is an `ONLINE`-channel order in-tenant and, when the caller is an authenticated customer, `order.customerId()` matches `ctx.userId()` — guest orders (`customerId == null`) are left payable by anyone holding the order id, consistent with the rest of the guest-checkout model. A separate `PAYMENT_AMOUNT_MISMATCH` (400) guards `amount == order.total()`. Both entry points share a private `capture(...)` that does the actual persist + outbox emit. Covered by `PaymentServiceTest` (POS-order rejection, ownership mismatch, amount mismatch, guest-order happy path, owned-order happy path, and that the staff path never touches `OrderClient`).

---

## ✅ 9. FIXED — Outbox drain is not concurrency-safe across service instances

**Where:** [shared/common-service/.../BaseOutboxRepository.java](shared/common-service/src/main/java/com/shelfj/service/BaseOutboxRepository.java), [OutboxStore.java](shared/common-service/src/main/java/com/shelfj/service/OutboxStore.java), [OutboxPublisher.java](shared/common-service/src/main/java/com/shelfj/service/OutboxPublisher.java)

```sql
SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?
```

The production model explicitly runs **multiple replicas** of each service. With no row locking, every replica's `OutboxPublisher` read and published the **same** pending rows → duplicate Kafka messages. Consumers are idempotent so correctness held, but it was wasted Kafka throughput and DB churn that scaled with replica count.

**Fix applied:** Replaced the two-step `pendingOutbox(limit)` + (later, unrelated transaction) `markPublished(ids)` contract with a single `OutboxStore.drainAndPublish(limit, publish)`. `BaseOutboxRepository` now runs the claim and the publish-mark in **one** `inTx` transaction: `SELECT ... FOR UPDATE SKIP LOCKED` claims the batch (a concurrent replica's drain simply skips locked rows and gets whatever's left, instead of blocking or double-claiming), the caller-supplied `publish` function is invoked with the claimed rows while the lock is held, and `UPDATE outbox SET published_at = now() WHERE id = ANY(?)` runs against exactly the ids it reports back — all before commit. `OutboxPublisher.drainQuietly` now just calls `store.drainAndPublish(100, this::publishBatch)`, where `publishBatch` is the same pipelined Kafka-send-then-await logic as before, just relocated into the callback. Rows the callback doesn't confirm stay unpublished (lock released at commit) and are claimable again next tick by any replica — at-least-once semantics are unchanged. While auditing implementers, found `iam-svc`'s `UserRepository` had its own dead-code duplicate `pendingOutbox` override identical to the base class's old logic; removed it now that it doesn't override anything in the new interface — `UserRepository` inherits `drainAndPublish` like every other repo. Verified with a full `mvn clean compile` across the reactor (catches exactly this kind of stale-override break) plus `mvn test` on the affected modules.

---

## ✅ 10. FIXED — `changePassword` reads the raw `X-User-Id` header instead of `TenantContext`

**Where:** [services/iam-svc/.../api/AuthResource.java](services/iam-svc/src/main/java/com/shelfj/iam/api/AuthResource.java)

```java
public ApiResponse<String> changePassword(@HeaderParam("X-User-Id") String userIdHeader, ...) {
```

Every other endpoint sourced identity from the gateway-validated `TenantContext`. This one re-parsed the raw header. It was safe only because the gateway strips/sets that header — but it was an inconsistent trust path that's easy to break later (e.g., if a service is ever exposed without the gateway in front). Code smell, not a live vuln.

**Fix applied:** Added `TenantContext.requireUserId()` (`shared/common-web/.../TenantContext.java`) — same fail-closed shape as the existing `requireTenantId()`, throwing 401 `NO_USER` when the context carries no authenticated principal. `AuthResource.changePassword` now takes just the request body and calls `ctx.requireUserId()`, dropping the `@HeaderParam`/manual UUID parsing entirely. While adding this, found `tenant-svc`'s `OnboardingResource` already had a private `requireUserId()` doing the exact same check by hand; replaced its two call sites with `ctx.requireUserId()` and deleted the now-dead private method, so there's one canonical fail-closed accessor instead of two copies. Verified with a full `mvn clean test` across the reactor (`AuthIT` covers `changePassword` end-to-end via the gateway-stamped-header simulation it already used).

---

## ✅ 11. FIXED — Throttle-cap eviction can drop a live (legitimate) limiter entry

**Where:** [RateLimitFilter](platform/gateway/src/main/java/com/shelfj/gateway/filters/RateLimitFilter.java) and [BruteForceProtectionService](platform/gateway/src/main/java/com/shelfj/gateway/filters/BruteForceProtectionService.java)

When the map was at `MAX_BUCKETS` and stale eviction freed nothing, the code removed an **arbitrary** entry (`keySet().iterator().next()`), which could be an active attacker's bucket — resetting their counter — or a legitimate user's. Under a high-cardinality flood this slightly weakened both protections.

**Fix applied:** Both classes now evict the least-recently-active entry instead of an arbitrary one. First attempt used the existing wall-clock timestamp (`lastRefillMs` / `lastActivityMs`) for the comparison, but a unit test driving a fast fill-to-cap loop caught a real flaw: `System.currentTimeMillis()` is too coarse under a tight burst — the exact scenario that fills the map to the cap — so many entries tie on the same millisecond, making the "oldest timestamp" pick effectively arbitrary again among the tied group. Fixed by adding a strictly-increasing `AtomicLong` touch-sequence stamped on every bucket/entry touch (`TokenBucket.lastTouchSeq`, `FailureState.lastTouchSeq`); eviction now picks the lowest sequence number, which has no ties regardless of timing. `buckets` / `stateByKey` were relaxed from `private` to package-private (matching this class's existing testability pattern for `config`/`serverRequest`) so tests can assert on cap behavior directly. Covered by `RateLimitFilterTest#capEvictsTheLeastRecentlyActiveBucketNotAnArbitraryOne` and `BruteForceProtectionServiceTest#capEvictsTheLeastRecentlyActiveEntryNotAnArbitraryOne`, each filling to `MAX_BUCKETS`/`MAX_ENTRIES` + 1 distinct keys and asserting the oldest key was evicted while the newest survived.

---

## Things checked and found OK (so they're not re-investigated later)

- **SQL injection** — none; all parameterized. Dynamic builders only append constant predicate strings.
- **Tenant isolation** — every tenant-owned query filters `tenant_id` first; store/zone ops pass `(tenantId, storeId)`; `PlatformResource` cross-tenant ops enforce `PLATFORM_ADMIN` in code (not just the path filter). Reference tables (`uom_*`, `item_attribute_groups`) are intentionally global.
- **Auth boundary** — `JwtAuthFilter` strips & re-stamps identity headers; public/storefront whitelists are exact-match and method-scoped; mutating endpoints are default-deny (`AdminAuthorizationFilter`).
- **Password & token handling** — Argon2id with a timing-equalizing dummy verify; refresh tokens are opaque, hashed-at-rest, single-use rotated, with reuse-detection that revokes the session family; password change revokes all sessions.
- **Concurrency on money/stock** — gift-card redeem, payment refund cap, and inventory FIFO all `FOR UPDATE`.
- **Idempotency** — additive event handlers dedupe via `processed_events` in-transaction; others are state-guarded/upsert idempotent.
- **Thread pools** — `OutboxPublisher`, `KafkaEventLoop`, and both inventory sweepers create daemon single-thread executors and shut them down in `@PreDestroy`/`close()`. No leak.
- **DB pool** — HikariCP with bounded pool, connection timeout, and max-lifetime; disposed on shutdown.
- **Error handling** — sanitized envelopes; no stack/SQL leakage to clients.
- **Secrets** — `.env` untracked; ports localhost-bound; JWT secret required at boot (≥32 chars) in both gateway and iam.

---

## Recommended fix order

1. **#1 + #2** (order-svc financial holes) — before any real-money use.
2. **#3** (flip pricing-enforce default to fail-closed) — one-line config change that backstops #1.
3. **#4** (cart authZ) — fix before wiring the storefront cart to the gateway.
4. **#5, #6, #7** — hardening for production rollout.
5. **#8–#11** — schedule into normal backlog.
