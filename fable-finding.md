# Fable Findings — Deep-Dive Audit (API + UI)

**Date:** 2026-07-02
**Scope:** All backend services (`platform/`, `services/`, `shared/`) and the Flutter app (`frontends/shelf-app/`).
**Method:** First-principles code read of the security boundary (gateway + iam), shared infra (Kafka loop, outbox, datasource, Redis pools), money/idempotency paths (order, payment, inventory), SQL construction, the Flutter client (leaks, token handling, network), and deployment config. This audit ignores the existing `.md` docs by design and reports only what the code actually does.

## Executive summary

**Remediation status (2026-07-02):** F1 ✅ · F2 ✅ · F3 ✅ · F4 ✅ · F5 ⏸️ deferred with rationale (trigger condition absent — see F5). Each fix below carries a per-finding status block with what changed and how it was verified.

This is a **mature, well-hardened codebase**. The things that usually break in this kind of platform are already done correctly:

- **No SQL injection.** Every query uses `PreparedStatement` with bound parameters; the only string-built SQL fragments are compile-time constants (`SELECT_COLS`), never request data.
- **No backend memory leaks found.** Every unbounded map is capacity-capped (`TenantStatusGate` MAX_ENTRIES, circuit breaker keyed per-service), rate-limit/brute-force state lives in Redis (not heap), Kafka consumers/producers and Hikari/Lettuce pools are closed via CDI `@Disposes`/`@PreDestroy`/`close()`.
- **No Flutter UI leaks found.** All 23 controller-owning widgets implement `dispose()`; both `Timer.periodic` uses are cancelled in `dispose()`.
- **Money is safe.** `BigDecimal` throughout; refund caps, layaway overpayment, and return-quantity caps are all enforced **inside a single locked transaction** (not check-then-act), so concurrent requests cannot over-refund or oversell. Inventory reserve/deduct locks rows `FOR UPDATE`.
- **AuthN/Z is strong.** `tenant_id` only ever comes from the gateway-verified JWT; identity headers are stripped and re-stamped; refresh tokens are opaque, hashed-at-rest, rotated, and reuse triggers family revocation; Argon2id with timing-equalization against account enumeration.

The findings below are therefore mostly **Medium/Low**. None is a critical, exploitable-today hole. They are ranked by severity.

---

## Findings

### F1 — [Medium · Security] Production datastore passwords silently fall back to well-known dev defaults — ✅ FIXED (2026-07-02)

> **Status: fixed.** [docker-compose.prod.yml](docker-compose.prod.yml) now re-references every datastore secret with the fail-fast `${VAR:?...}` guard (the same form the JWT/config-token secrets already use), so `docker compose ... up` aborts at config-load time rather than booting on a repo-public default. **Scope grew during implementation:** the first pass only guarded the Postgres superuser, Redis, Grafana and pgAdmin. Verifying "no dev default leaks" surfaced **12 more** — the per-service Postgres LOGIN roles (`iam_dev_change_me`, `cart_dev_change_me`, …) created by [postgres-init-roles.sql](infra/postgres-init-roles.sql); those are now guarded too (`<SVC>_DB_PASSWORD`), with a comment pointing at the required `ALTER ROLE` rotation.
>
> **Verified with `docker compose config`:** (1) all 16 secrets set → resolves cleanly with **zero** `*_dev_change_me` strings remaining; (2) blanking any one secret → aborts naming exactly that variable; (3) the dev base file alone still parses (out-of-the-box dev workflow unchanged). Loopback port binding remains the second layer; strong secrets are now the first.


**Where:** [docker-compose.prod.yml](docker-compose.prod.yml) vs [docker-compose.yml:147](docker-compose.yml#L147), [330](docker-compose.yml#L330), [446](docker-compose.yml#L446), [473](docker-compose.yml#L473)

The base compose defaults every datastore credential to a public constant:
```
POSTGRES_PASSWORD:-shelfj_dev_change_me
REDIS_PASSWORD:-redis_dev_change_me
GRAFANA_PASSWORD:-admin_dev_change_me
PGADMIN_PASSWORD:-admin_dev_change_me
```
The JWT secret is correctly forced (`${SHELFJ_JWT_SECRET:?...}` — deploy fails if unset). The datastore passwords are **not**: they use `:-default`, so if an operator brings up the prod overlay without those vars in `.env`, Postgres/Redis/Grafana/pgAdmin all run with credentials that are published in this repo. The prod overlay overrides CORS and rate-limit but never re-declares these as required.

**Impact:** Partially mitigated because all datastore host ports are bound to `127.0.0.1` (loopback) in the base compose, so they aren't reachable from the public IP. But it's weak defense-in-depth: any SSRF, a compromised sidecar, or a shared-host foothold gets DB/cache access with a password anyone can read here.

**Fix:** Make them fail-fast in the prod overlay exactly like the JWT secret, e.g. add to `docker-compose.prod.yml`:
```yaml
postgres:
  environment:
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set a strong POSTGRES_PASSWORD in .env}
redis:
  command: ["redis-server", "--requirepass", "${REDIS_PASSWORD:?set REDIS_PASSWORD in .env}"]
grafana:
  environment:
    GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:?set GRAFANA_PASSWORD in .env}
```
(Same for pgAdmin, and drop pgAdmin/Grafana from the prod profile entirely if they aren't operationally needed.)

---

### F2 — [Medium · Implementation/Uptime] Flutter token-refresh interceptor drops concurrent 401s — ✅ FIXED (2026-07-02)

> **Status: fixed.** Replaced the `_isRefreshing` boolean with a single-flight `Future<String?> _refreshing` in [api_client.dart](frontends/shelf-app/lib/core/network/api_client.dart): concurrent 401s now await the same refresh and replay with the new token; the refresh call itself is excluded (path check) to prevent recursion; `_doRefresh` reads the refresh token from storage at call time so no already-rotated token is ever re-presented (which would trip iam-svc's reuse-detection and revoke the session). Regression test [auth_interceptor_test.dart](frontends/shelf-app/test/core/auth_interceptor_test.dart) proves 6 concurrent 401s → exactly 1 refresh, all recover; and that a failed refresh clears tokens and surfaces the 401. `flutter analyze` clean; both tests green.
>
> **Scope correction after verification:** the bug was confined to `api_client.dart`. On closer read the other two files do **not** share it — `storefront_providers.dart`'s `storefrontDioProvider` has *no* refresh interceptor at all (it just stamps the token; an expired storefront-customer token simply fails rather than dropping a concurrent refresh), and `auth_notifier.dart`'s `refresh()` is a manual one-shot called after onboarding that delegates to the shared interceptor. Adding auto-refresh to the storefront Dio would be a feature change, not a bug fix, so it's out of scope here.


**Where:** [lib/core/network/api_client.dart:47-77](frontends/shelf-app/lib/core/network/api_client.dart#L47-L77)

```dart
if (err.response?.statusCode == 401 && !_isRefreshing) {
  _isRefreshing = true;
  ... refresh, retry this one request ...
} else {
  handler.next(err);   // <-- every OTHER 401 during the refresh fails here
}
```

The interceptor refreshes and retries only the **first** request that sees a 401. Any other request that 401s while `_isRefreshing == true` falls into the `else` and is returned to the caller as a hard error — it is never queued or retried after the new token lands.

**Impact:** Most screens fire several requests in parallel (e.g. catalog + cart + promotions on load). When the access token has just expired, exactly one of them recovers and the rest fail with spurious errors — blank sections, false "failed to load," and in flows that treat a 401 as sign-out, a premature logout. This is an intermittent, hard-to-reproduce reliability bug that gets worse under real latency. The same single-flag pattern appears in the storefront and auth notifiers ([storefront_providers.dart](frontends/shelf-app/lib/features/storefront/storefront_providers.dart), [core/auth/auth_notifier.dart](frontends/shelf-app/lib/core/auth/auth_notifier.dart)) and should be fixed the same way.

**Fix:** Serialize refresh with a queue. Hold a single in-flight `Future<String?> _refreshing` (a `Completer`); requests that 401 during a refresh `await` it and then replay with the new token instead of failing:
```dart
Future<String?>? _refreshing;

Future<String?> _refreshOnce() {
  return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
}
// onError: final newToken = await _refreshOnce();
//          if (newToken == null) { await _clearTokens(); return handler.next(err); }
//          err.requestOptions.headers['Authorization'] = 'Bearer $newToken';
//          handler.resolve(await _dio.fetch(err.requestOptions));
```
Also guard against the refresh call itself 401-looping (the refresh POST goes through this same interceptor) — send it on a bare `Dio` with no auth interceptor.

---

### F3 — [Low · Robustness] Financial write paths read tenant from the nullable `ctx.tenantId()` and skip a non-negative check — ✅ FIXED (2026-07-02)

> **Status: fixed (partial scope — the rest was already covered).** `createLayaway` and `issueGiftCard` in [OrderService.java](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java) now use `ctx.requireTenantId()` (fail 401) instead of the nullable `ctx.tenantId()`. `createSpecialOrder` already received `requireTenantId()` from its resource, so no change there. Regression test [OrderServiceTenantGuardTest.java](services/order-svc/src/test/java/com/shelfj/order/service/OrderServiceTenantGuardTest.java) proves both reject a tenant-less call before any repo write (BUILD SUCCESS, 2/2).
>
> **The negative-deposit half needed no code change:** on inspection the DTOs already enforce it — `CreateLayawayRequest.initialDeposit`, `AddDepositRequest.amount`, and `IssueGiftCardRequest.amount` are all `@NotNull @Positive`, and every resource calls `Validations.validate(req)` before delegating, so a negative/zero deposit is already rejected at the boundary. Original finding overstated this; corrected here.


**Where:** [OrderService.createLayaway](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java#L413-L473) (`ctx.tenantId()` at L417), [issueGiftCard](services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java#L513) (L514), plus `initialDeposit` handling at L440.

`placeOrder` correctly calls `ctx.requireTenantId()`, but `createLayaway`, `issueGiftCard`, and `createSpecialOrder` read the **nullable** `ctx.tenantId()`. These endpoints are role-gated so a tenant is normally present; but if one is ever reached without a tenant claim (misconfig, a new call path, a direct hit bypassing the gateway in a test/staging rig), they persist rows with `tenant_id = null` or NPE mid-transaction instead of returning a clean `401 NO_TENANT`.

Separately, `createLayaway` computes `balance = total.subtract(req.initialDeposit())` and only rejects `balance < 0`. A **negative** `initialDeposit` passes validation, inflates `balance` above `total`, and writes a negative-amount deposit row.

**Fix:** Use `ctx.requireTenantId()` in these three methods (consistent with `placeOrder`), and reject `req.initialDeposit().signum() < 0` (and `req.amount().signum() <= 0` for deposits/gift cards) at the top of each method or via a Bean Validation `@PositiveOrZero`/`@Positive` on the DTO.

---

### F4 — [Low · Security] Guest cart ownership rests on a client-chosen session token — ✅ FIXED (2026-07-02)

> **Status: fixed.** [CartService.createOrGetCart](services/cart-svc/src/main/java/com/shelfj/cart/service/CartService.java) now mints the guest session token server-side (256-bit `SecureRandom`, URL-safe base64) whenever a new guest cart is created; a client-supplied `sessionId` is only ever honoured to resolve an *existing* cart, never to create one under a caller-chosen (guessable) id. `CartResponse.sessionId` already carried the token back to the client, so no DTO/response-shape change was needed, and **no frontend calls this API today** (the storefront cart is client-side), so zero client blast radius. Regression tests in [CartServiceTest.java](services/cart-svc/src/test/java/com/shelfj/cart/CartServiceTest.java) assert a guest create yields a high-entropy token and that a client-chosen weak id is ignored for new-cart creation (9/9 pass). `requireOwnership` is unchanged — it just now compares against an unguessable stored token.


**Where:** [CartService.resolveCart / requireOwnership](services/cart-svc/src/main/java/com/shelfj/cart/service/CartService.java#L216-L250), session value from `req.sessionId()` at [L46](services/cart-svc/src/main/java/com/shelfj/cart/service/CartService.java#L46).

A guest cart is keyed by a `sessionId` supplied by the client. Ownership of that cart is proven solely by presenting the same `sessionId` (`requireOwnership` compares `cart.sessionId().equals(suppliedSessionId)`). Because the value is client-chosen rather than a server-generated high-entropy token, a guest who picks a weak/guessable/sequential session (or an app that derives it predictably) exposes their cart to another party who supplies the same string.

**Impact:** Low — a guest cart holds product lines, not payment data or PII, and this is a deliberate guest-shopping design. But it doesn't meet the "server mints the session secret" bar.

**Fix:** Have cart-svc generate the guest session token server-side (`SecureRandom`, ≥128 bits, like `Tokens.newOpaqueToken()` in iam) on first cart creation and return it to the client, instead of trusting `req.sessionId()`. Reject client-supplied session ids that weren't issued by the service.

---

### F5 — [Low → Latent · Uptime/Efficiency] Gateway fully buffers every upstream response body in heap — ⏸️ DEFERRED (2026-07-02, with rationale)

> **Status: intentionally not changed yet — the trigger condition doesn't exist in the codebase.** A deeper pass for the actual fix showed the real-world impact is near-zero: there is **no** export / CSV / `octet-stream` / download / streaming endpoint anywhere in `services/`, and **every** list endpoint clamps its page size to ≤100 (`Cursor.clampLimit`, `Math.min(limit, 100)`) under mandatory cursor pagination. So the largest realistic proxied body is a ~100-item JSON page (a few hundred KB), and the double-buffering is bounded, not unbounded.
>
> Meanwhile the fix (`upstream.as(String.class)` → JAX-RS `StreamingOutput` over `upstream.inputStream()`) rewrites `relay()` — the single path **every** API response flows through — and its correctness (upstream connection close timing, mid-stream read errors after the 200 status line is already committed) can only be truly validated against the live Helidon runtime on the Docker stack, which this pass can't exercise. `ProxyRouteTest` covers only route parsing; there is **no** existing body round-trip test. Changing the highest-blast-radius component with no coverage, to fix a bounded/theoretical problem, is the wrong trade.
>
> **Recommendation:** leave `relay()` as-is for now; revisit (and do the streaming rewrite + a real body round-trip test) **before** the first unbounded/export/report-download endpoint is added — that's the point the concern becomes real. A smaller defensive alternative (cap the buffered body and 502 above a threshold, to bound a future rogue/compromised upstream) is available if belt-and-suspenders is wanted sooner.

**Original finding, for reference:**


**Where:** [ProxyResource.relay](platform/gateway/src/main/java/com/shelfj/gateway/ProxyResource.java#L289-L291) — `rb.entity(upstream.as(String.class))`.

The proxy reads the entire upstream response into a `String`, then JAX-RS re-serializes it out. For normal JSON this is fine, but reporting exports, large catalog pages, or any big list response are held **twice** in memory per request. Under concurrency (N big responses in flight), that's N × full-body heap, which can drive GC pressure / OOM on the gateway — the one process every request funnels through.

**Fix:** Stream the upstream body through instead of materializing it: relay `upstream.inputStream()` (or Helidon's streaming entity) into the response `entity` so the gateway proxies bytes without buffering the whole payload. If a full rewrite is too invasive now, at minimum cap proxied response size and return `502` above a threshold so a huge upstream body can't exhaust gateway heap.

---

## Accepted / by-design (verified, not bugs)

These looked suspicious on first read but are correct on inspection — recording them so a future reviewer doesn't re-flag them:

- **`TenantStatusGate` fails open** on a tenant-svc error (a suspended storefront keeps serving during a tenant-svc outage). This is deliberate and documented in the class; the hard block that matters (staff login) is enforced independently in iam-svc. Accepted trade-off — flagging only so it's a conscious ops decision.
- **`KafkaEventLoop` `attempts` map / `dlqProducer`** — not leaks: `attempts` is keyed by `TopicPartition` (bounded by partition count), the DLQ producer is lazily created and closed on `close()`.
- **Return over-refund / refund-cap / layaway overpayment** — all guarded atomically in the repo (`item.qty().add(alreadyReturned) > purchasedQty`, `createRefundGuarded` with the payment row locked, `... AND balance >= ?` on the deposit update). No TOCTOU window.
- **Argon2 timing** — `login`/`platformLogin` call `passwords.burn()` for unknown emails and non-ACTIVE accounts, equalizing response time against account enumeration.
- **Flutter token storage** — uses `flutter_secure_storage` (Keychain/Keystore), not `SharedPreferences`. Correct.

---

## Suggested priority

1. **F2** (Flutter refresh queue) — highest user-visible reliability win, self-contained client change.
2. **F1** (force strong prod datastore passwords) — one-file hardening change, closes a real defense-in-depth gap before any prod rollout.
3. **F3 / F4 / F5** — schedule into the next hardening pass; none is urgent given current mitigations.
