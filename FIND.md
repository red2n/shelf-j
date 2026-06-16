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

## 🟠 4. Cart access has no object-level authorization (IDOR)

**Where:** [CartService](services/cart-svc/src/main/java/com/shelfj/cart/service/CartService.java#L82-L204) — `addItem`, `updateItemQty`, `removeItem`, `viewCart`/`resolveCart`.

Carts are located purely by `cartId` (or `sessionId`) scoped to tenant:

```java
Cart cart = repo.findById(tenantId, cartId)
    .orElseThrow(() -> ApiException.notFound("CART_NOT_FOUND", ...));
// never checks cart.customerId() == ctx.userId()  (or that the session belongs to the caller)
```

The cart's `customerId`/`sessionId` is **never compared to the authenticated caller**. Any authenticated user who knows/guesses another user's `cartId` (or guest `sessionId`) in the same tenant can read, add to, change quantities in, and delete items from that cart.

> Exposure today is limited because the gateway whitelist (`JwtAuthFilter`) does not yet route guest/customer storefront traffic to `cart-svc` — so this is currently reachable mainly by staff tokens. But the moment cart paths are added to the storefront whitelist (required for the online cart to function), this becomes directly exploitable. Treat it as a latent authZ gap to fix now.

**Fix:** Enforce ownership on every cart operation:

```java
if (cart.customerId() != null) {
    if (!cart.customerId().equals(ctx.userId()))
        throw ApiException.notFound("CART_NOT_FOUND", "cart not found"); // 404, don't confirm existence
} else if (!cart.sessionId().equals(req.sessionId())) {
    throw ApiException.notFound("CART_NOT_FOUND", "cart not found");
}
```

---

## 🟠 5. `TenantStatusGate` cache is unbounded (memory leak + amplification)

**Where:** [platform/gateway/.../filters/TenantStatusGate.java](platform/gateway/src/main/java/com/shelfj/gateway/filters/TenantStatusGate.java#L33-L45)

```java
private final Map<String, Cached> cache = new ConcurrentHashMap<>();
...
cache.put(tenantId, new Cached(active, now + TTL_MILLIS));   // no size cap, no eviction
```

Unlike `RateLimitFilter` and `BruteForceProtectionService` (which both cap at `MAX_BUCKETS = 10_000` and evict stale entries), this cache has **no bound and no expired-entry eviction** — expired entries linger until overwritten by the same key. The key is `X-Storefront-Tenant`, which on guest paths is **client-controlled**. A caller (or botnet) rotating that header across many distinct values grows the map without limit (gateway OOM) and each new value also triggers a `GET /storefront/active` to tenant-svc (request amplification against tenant-svc).

**Fix:** Mirror the bounded pattern already used by the sibling filters — cap the map size, evict expired entries on insert, and drop an entry when at cap:

```java
if (cache.size() >= MAX_ENTRIES && !cache.containsKey(tenantId)) {
    cache.values().removeIf(c -> c.expiresAt() <= now);
    if (cache.size() >= MAX_ENTRIES) cache.keySet().iterator().remove();
}
```

(`RateLimitFilter` already throttles per-IP ahead of this, which bounds a single IP — but distributed callers and the unbounded growth remain.)

---

## 🟠 6. Weak default platform-admin password in auto-bootstrap

**Where:** [docker-compose.yml](docker-compose.yml#L728-L733) — `ADMIN_PASSWORD: ${PLATFORM_ADMIN_PASSWORD:-Admin1234!}`, which the bootstrap job POSTs to `/api/iam-svc/bootstrap/admin`.

The first `PLATFORM_ADMIN` is auto-created with a **known default password** (`Admin1234!`) unless `PLATFORM_ADMIN_PASSWORD` is set. The bootstrap endpoint is one-shot (rejects if an admin exists), so the account — full platform superuser — is created with a publicly-known credential on any deploy that forgets to override it.

**Fix:** Make the variable required (no default), the same way the JWT secret is handled:

```yaml
ADMIN_PASSWORD: ${PLATFORM_ADMIN_PASSWORD:?set PLATFORM_ADMIN_PASSWORD - see .env.example}
```

Optionally force a password change on first login.

---

## 🟠 7. Kafka poison-pill blocks its partition indefinitely

**Where:** [shared/common-service/.../KafkaEventLoop.java](shared/common-service/src/main/java/com/shelfj/service/KafkaEventLoop.java#L75-L113)

The retry model is "throw = redeliver, seek back to the failed offset." A record whose handler **always** throws (genuinely malformed payload that isn't caught, a referenced row that never appears, a persistent bug) is re-polled every 2s **forever**, and because the loop rewinds to the first failure per partition, **all later records on that partition are blocked** behind it.

**Impact:** One bad event can silently stall an entire partition's event processing (stock updates, order confirmations) with no alert and no escape.

**Fix:** Add a bounded retry with a dead-letter / park step:
- Track attempt count per `(topic, partition, offset)`; after N attempts, log at `ERROR`, publish the record to a `<topic>.DLT` dead-letter topic (or a `failed_events` table), commit past it, and continue.
- Emit a metric so a stuck partition is observable.

---

## 🟡 8. Online payment capture is trust-based; amount not validated at capture

**Where:** [services/payment-svc/.../service/PaymentService.java](services/payment-svc/src/main/java/com/shelfj/payment/service/PaymentService.java#L30-L59)

`recordTender` accepts `req.amount()` and `req.orderId()` with no verification that the order exists, belongs to the caller, or that the amount matches the order total — it just records a `CAPTURED` tender and emits `PaymentCaptured`. There is no payment-service-provider (PSP) integration, so an online payment is effectively self-attested. The only downstream guard is `OrderService.handlePaymentCaptured` requiring `amount ≥ total`.

**Impact:** On the `/payments/online` path (reachable by a customer token), a caller can self-confirm an order without a real charge. This is partly inherent to "no PSP yet," but the missing order-existence / ownership / amount checks make it worse and pair badly with #1.

**Fix:** Until a real PSP is integrated: validate `orderId` exists in-tenant, that (for online) it belongs to `ctx.userId()`, and that `amount == order.total()` (call order-svc or carry the total through the capture intent). Long-term: capture against a PSP authorization token, never a client-asserted amount.

---

## 🟡 9. Outbox drain is not concurrency-safe across service instances

**Where:** [shared/common-service/.../BaseOutboxRepository.java](shared/common-service/src/main/java/com/shelfj/service/BaseOutboxRepository.java#L35-L45)

```sql
SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?
```

The production model explicitly runs **multiple replicas** of each service. With no row locking, every replica's `OutboxPublisher` reads and publishes the **same** pending rows → duplicate Kafka messages. Consumers are idempotent so correctness holds, but it is wasted Kafka throughput and DB churn that scales with replica count.

**Fix:** Claim rows exclusively per drain:

```sql
SELECT id, topic, payload FROM outbox
WHERE published_at IS NULL
ORDER BY created_at ASC
LIMIT ? FOR UPDATE SKIP LOCKED
```

(keep the `markPublished` UPDATE inside the same transaction as the claim).

---

## 🟡 10. `changePassword` reads the raw `X-User-Id` header instead of `TenantContext`

**Where:** [services/iam-svc/.../api/AuthResource.java](services/iam-svc/src/main/java/com/shelfj/iam/api/AuthResource.java#L84-L101)

```java
public ApiResponse<String> changePassword(@HeaderParam("X-User-Id") String userIdHeader, ...) {
```

Every other endpoint sources identity from the gateway-validated `TenantContext`. This one re-parses the raw header. It is safe today only because the gateway strips/sets that header — but it's an inconsistent trust path that's easy to break later (e.g., if a service is ever exposed without the gateway in front). Code smell, not a live vuln.

**Fix:** Use `ctx.userId()` (with `requireTenantId()`-style fail-closed) like the rest of the codebase, and drop the `@HeaderParam`.

---

## 🟡 11. Throttle-cap eviction can drop a live (legitimate) limiter entry

**Where:** [RateLimitFilter](platform/gateway/src/main/java/com/shelfj/gateway/filters/RateLimitFilter.java#L42-L53) and [BruteForceProtectionService](platform/gateway/src/main/java/com/shelfj/gateway/filters/BruteForceProtectionService.java#L30-L39)

When the map is at `MAX_BUCKETS` and stale eviction frees nothing, the code removes an **arbitrary** entry (`keySet().iterator().next()`), which may be an active attacker's bucket — resetting their counter — or a legitimate user's. Under a high-cardinality flood this slightly weakens both protections.

**Fix:** Low priority. If hardened: evict the least-recently-used / soonest-to-refill entry rather than an arbitrary one, or size the cap to the expected legitimate-client population so eviction only happens under genuine attack.

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
