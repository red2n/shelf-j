# Shelf-J — Independent Deep-Dive Audit

**Scope:** A fresh, from-scratch assessment of the *actual* code on disk — no prior audit or its finding IDs were consulted. Every claim below was verified by reading the referenced file(s), not inferred from docs.
**Method:** Traced the trust boundary (gateway → services), object- and role-level authorization on every reachable path, SQL/locking/money handling, the Kafka publisher↔consumer map, list-endpoint pagination, inter-service client resilience, and the Flutter client's storage + error surfaces.
**Date:** 2026-07-13 · **Branch:** `feat/close-audit-findings` · **HEAD:** `c3bbe1c`

---

## Snapshot of what was reviewed

| Area | Reality on disk |
|---|---|
| Backend | 12 Helidon MP services + 3 platform modules (gateway/discovery/config), **251 main Java files**, **369 endpoint annotations**, **156 Flyway migrations** |
| Frontend | One Flutter app (admin / POS / storefront / platform shells), **98 Dart files**, Riverpod + go_router + dio |
| Shared | `common-web` (envelope, filters, tenant context), `common-service` (JDBC/outbox/Kafka loop), `events-contract`, `common-test` |
| Tests | **49 backend test files** (Testcontainers; 2–8 per service, order-svc 8), **11 Flutter test files** |
| Ops | docker-compose with healthchecks; only the **gateway (8090)** and **UI (8088)** bind host ports — every business service + infra store is internal or `127.0.0.1`-bound |

---

## Executive verdict

The cross-cutting foundation is genuinely solid: the gateway is the single public door, it **strips client-supplied identity headers and re-stamps only JWT-verified ones**, refuses to boot on a weak JWT secret, and services take `tenant_id` exclusively from those verified headers. Writes are **default-deny** by role, money is `BigDecimal`/`NUMERIC`, and inter-service clients carry timeouts (mostly with retry/circuit-breaker). This is a well-built system.

**The one finding that rises above "quality debt" is [F1]** — a broken **object-level** authorization on order reads: role-based (tenant/write) checks are strong, but a per-record ownership check was missing, so an authenticated customer could read another customer's order in their tenant — including its delivery address and contact phone — by ID. **F1 is now fixed** (`OrderService.requireReadAccess`, see the finding for details). Everything else is maintainability / scale-tail / consistency work.

---

## Verified-strong foundation (re-checked fresh, not gaps) ✅

- **Trust boundary.** `platform/gateway/.../filters/JwtAuthFilter.java` strips any inbound `X-Tenant-Id`/`X-User-Id`/`X-Roles`, validates the Bearer JWT (HMAC256 + issuer), and re-injects identity from claims only; it throws on a `< 32`-char secret at `@PostConstruct`. Guest storefront access is a tight path whitelist keyed off `X-Storefront-Tenant`.
- **Authorization default-deny (writes).** `shared/common-web/.../AdminAuthorizationFilter.java` denies every mutating request without a staff role unless the path is on an explicit bootstrap/identity/guest allowlist; `/admin/**` and `.../refunds`/`.../void` require a management role. New write endpoints ship protected.
- **No direct service exposure.** In `docker-compose.yml` only `gateway` and the UI publish host ports; Postgres/Kafka/Consul/Redis are internal or loopback-bound.
- **Money & concurrency.** `BigDecimal`/`NUMERIC` throughout; reserve/deduct locks rows `FOR UPDATE`; checkout is idempotency-key gated.
- **Client resilience.** Every `*/client/*.java` sets a request `@Timeout`; the order/payment clients add `@Retry` + `@CircuitBreaker`/`@Fallback`.

---

# Findings

### F1 — Broken object-level authorization on order reads (IDOR, PII leak) 🔴 High — ✅ FIXED
`GET /orders/{id}`, `GET /orders/{id}/history`, and `GET /orders/{id}/returns` scoped **only by tenant**, with no ownership check and no role gate (reads aren't covered by the write-only default-deny filter).

- **Evidence:** `services/order-svc/.../api/OrderResource.java:114` — `svc.getOrder(ctx.tenantId(), uuid(id))` → `OrderService.getOrder(UUID tenantId, UUID orderId)` (`service/OrderService.java:306`) filtered by tenant + id **only**. Compare `GET /orders/mine` (`OrderResource.java:77`) which correctly restricts to `customerId = ctx.userId()` — showing per-customer isolation is the intended model, and `/{id}` bypassed it.
- **Exposure:** the returned DTO carries `deliveryAddress`, `contactPhone`, `customerName`, `customerId` (`order-svc/.../dto/Dtos.java:46,228,231`). An authenticated `CUSTOMER` of a tenant could read another customer's order details by ID.
- **Mitigating factors:** order IDs are random UUIDv4 (not enumerable) and the leak is within a single tenant; a guest (no JWT) is blocked at the gateway. Exploitability was further narrowed by the token model: customer JWTs carry no tenant claim (`tenantId = null`), and the gateway strips client-supplied `X-Tenant-Id`, so a plain customer reaching `/{id}` resolved no tenant and got 404. The unguarded reads were still wrong as a standing invariant — safety rested on distant gateway/token details, not on the endpoint.
- **Fix (applied):** `OrderService.requireReadAccess` (mirrors `CartService.requireOwnership`): staff roles read any order in their tenant; a caller with a principal but no staff role must own the order (`order.customerId == ctx.userId()`), with denial as **404** (no existence oracle); a call with no principal at all (only `X-Tenant-Id`) is the service-to-service shape used by payment-svc's `OrderClient` and stays tenant-scoped. Enforced on all three reads via authorized service methods; covered by `OrderIT.orderByIdReadsAreObjectLevelAuthorized` (owner 200 / other customer 404 / staff 200 / s2s 200).

### F2 — God-files concentrate many aggregates in one class 🟠 Medium (maintainability)
A handful of classes carry disproportionate size, mixing many aggregates and making review/merge risky.

- **Evidence:** `inventory-svc/.../repo/InventoryRepository.java` **3,935 lines**, `product-svc/.../repo/ProductRepository.java` **2,230**, `inventory-svc/.../service/InventoryService.java` **1,973**, `inventory-svc/.../api/AdminResource.java` **1,440**; bundled `Dtos.java`/`Domain.java` per service hold dozens of records.
- **Impact:** SRP erosion, hard to reason about locking/transaction scope, high merge-conflict surface. Not a runtime bug.
- **Fix:** split per aggregate into repositories extending the shared `BaseOutboxRepository` (batches / levels / planning / serials / lots …), and split `AdminResource` by sub-domain path group.

### F3 — Residual fetch-all on low-cardinality admin lists 🟠 Medium-Low (scale-tail / consistency) — ✅ FIXED
The high-cardinality lists (orders, customers, products, inventory-levels) were cursor-paginated, but several admin lists returned the whole set with no `after`/`limit`.

- **Evidence (no cursor params):** `tenant-svc/.../api/AdminResource.java` `/staff` & `/stores` (and per-store `/zones`), `pricing-svc/.../api/PriceListResource.java` `/price-lists`.
- **Impact:** fine today (few stores/staff/price-lists per tenant) but inconsistent with the documented cursor convention and unbounded if a tenant grows large.
- **Fix (applied):** all four lists now use the standard keyset cursor (`(created_at, id)` ascending, `?after=&limit=`, `meta.nextCursor`), via new shared helpers in `common-web` `Cursor` (`decodeCreatedAtId` + generic `Page` builder) so the boilerplate isn't stamped per service. Consumers updated: the Flutter master-data providers (stores/zones/staff/price-lists) walk pages to completion through a new `core/network/paged.dart` helper (dropdown UX unchanged); product-svc's `PricingClient` default-list scan requests `limit=100`. The public `GET /storefront/stores` list is unchanged (separate surface). Covered by `OnboardingIT.adminListsAreCursorPaginated` (page walk, no duplicates, 400 on malformed cursor) and `PricingIT.priceListsAreCursorPaginated`.

### F4 — Inconsistent error surfacing in the Flutter client 🟠 Low
Structured API errors (`error.code`) are consumed by only a few screens; most still show the raw exception.

- **Evidence:** ~**36** raw `$e` / `.toString()` interpolations inside SnackBar/`Text` error paths across `lib/`, vs **4** files referencing the `api_error.dart` helper.
- **Impact:** users see framework/exception text; no machine-code-driven messaging or retry affordances; harder i18n.
- **Fix:** route all catch → snackbar paths through the `api_error.dart` helper (map `error.code` → localized message).

### F5 — Web build stores the auth token in plaintext localStorage 🟡 Low (accepted trade-off, worth hardening)
`lib/core/storage/app_storage.dart` uses OS Keychain/Keystore on native but **SharedPreferences (plain `localStorage`) on web** — a deliberate, well-documented choice (flutter_secure_storage's web backend needs a secure context and is itself plaintext under the hood).

- **Impact:** on the web shell the JWT is readable by any script in the origin → XSS becomes token theft. Acceptable given the constraint, but the risk should be bounded.
- **Fix:** pair with a strict CSP on the served UI, keep access-token TTL short with refresh rotation (a single-flight refresh already exists), and avoid persisting refresh tokens on web if feasible.

### F6 — Many published domain events have no consumer 🟢 Low (observation)
The outbox publishes a broad set of events; a large subset has no subscriber today.

- **Evidence:** consumers exist for user-registered, order confirmed/fulfilled/returned/cancelled, payment captured/failed/refunded, stock + sales events, tenant-created, staff-assigned, goods-received, order-placed, shortage-alert. **Unconsumed** include `kanban-*`, `lot-*`, `serial-*`, `move-order-*`, `accounting-period-*`, `price-changed`, `promotion-*`, `product-*`/`variant-created`, `store-credit-*`, `loyalty-*`, `zone-created`, `user-role-granted`.
- **Impact:** these are mostly legitimate future/audit hooks, but they add outbox+Kafka traffic for no reader. No correctness issue.
- **Fix:** none required; optionally annotate intent so they aren't mistaken for half-wired features.

### F7 — Frontend test coverage is thin 🟢 Low
Backend has a reasonable Testcontainers spread (49 files, 2–8 per service); the Flutter app has **11 test files for 98 Dart files**, concentrated on storefront checkout/catalog logic.

- **Impact:** admin/POS screens and most providers have no widget/golden/provider tests; regressions in those surfaces ship unguarded.
- **Fix:** add provider-level tests for the paginated list notifiers and golden/widget tests for the main admin & POS screens (the harness already exists).

---

# Prioritized remediation roadmap

| # | Item | Layer | Severity | Effort | Notes |
|---|---|---|---|---|---|
| F1 | ✅ Done — order ownership enforced on `GET /orders/{id}` + `/history` + `/returns` | Backend | 🔴 High | S | `OrderService.requireReadAccess`; denial is 404; s2s (tenant-only) shape preserved |
| F2 | Split god-files per aggregate (repos → `BaseOutboxRepository`, `AdminResource` by path group) | Backend | 🟠 Med | L | Pure refactor; reduces merge/locking risk |
| F3 | ✅ Done — residual admin lists cursor-paginated (tenant staff/stores/zones, price-lists) | Backend | 🟠 Med-Low | M | Shared `Cursor.Page` helper; Flutter providers walk pages |
| F4 | Route all client error snackbars through `api_error.dart` | UI | 🟠 Low | M | Mechanical, per-screen |
| F5 | CSP + short token TTL to bound the web plaintext-token risk | UI/Ops | 🟡 Low | S | Trade-off already documented in `AppStorage` |
| F6 | Document orphan-event intent (or prune) | Backend | 🟢 Low | S | No action strictly required |
| F7 | Provider + golden/widget tests for admin/POS + paginated notifiers | UI | 🟢 Low | M | Harness exists |

---

## Reference (verified anchors)
- Trust boundary / header stripping: `platform/gateway/src/main/java/com/shelfj/gateway/filters/JwtAuthFilter.java`
- Write default-deny + role model: `shared/common-web/src/main/java/com/shelfj/web/AdminAuthorizationFilter.java`
- **F1** order read (owner check now in): `services/order-svc/src/main/java/com/shelfj/order/api/OrderResource.java:114` · `service/OrderService.java` (`requireReadAccess`)
- Owner-scoped counter-example: `services/order-svc/.../api/OrderResource.java:77` (`/orders/mine`)
- **F5** web token storage: `frontends/shelf-app/lib/core/storage/app_storage.dart`
- Kafka event loop / DLQ: `shared/common-service/src/main/java/com/shelfj/service/KafkaEventLoop.java`
