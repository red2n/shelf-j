# Shelf-J — Industry-Standard Deep-Dive Audit

**Scope:** Independent assessment of the *actual* codebase (not the spec docs). This revision focuses on **service-to-service wiring and event topology** — which advertised flows are actually connected end-to-end — on top of the earlier API-design / UI-UX quality pass.
**Method:** Mapped every Kafka publisher against every consumer, traced the checkout/return/refund and loyalty/notification paths through code, and verified each claim with targeted greps and file reads.
**Date:** 2026-07-08 · **Branch:** `main`

> **Revision note (2026-07-08):** the original API/UI findings (A1–A4, U1, U3) are resolved and have been removed from this document along with their implementation logs. Items still carrying real remaining scope are condensed in [§Carry-over open items](#carry-over-open-items-from-the-api--ui-pass). The bulk of this revision is the new **integration/wiring findings (N1–N10)**.

---

## Snapshot of what was reviewed

| Area | Reality on disk |
|---|---|
| Backend | 12 Helidon MP services, ~360 endpoint annotations, 228 main Java files, 146 Flyway migrations |
| Frontend | Flutter app, 4 shells (admin / POS / storefront / platform), 74 Dart files, Riverpod + go_router + dio |
| Platform | gateway (JWT/CORS/rate-limit/brute-force + `/v1` alias), Consul, PgBouncer, Kafka outbox **with DLQ**, config-svc, k6 load tests |
| Tests | 45 backend test files (Testcontainers) + **11 frontend test files** (was 0 at the prior audit) |

---

## Executive verdict

The platform is a working, sizeable system, and the **cross-cutting foundation is now genuinely strong** (see below). The correctness/security class of problems from the previous audit is largely closed. **The headline finding has shifted:** the remaining risk is no longer per-request correctness — it is **half-wired features**. Several capabilities that exist as endpoints, tables, and published events are **not connected end-to-end**: notifications are never actually sent, customer loyalty never auto-accrues, refunds never propagate to the order, sales analytics don't exist, and the central config service is deployed but unused.

**Blocking-for-credible-launch items (this revision):** N1 (notifications never delivered), N10 (payment provider still mocked). ~~N2~~, ~~N5~~, ~~N6~~, ~~N7~~ resolved 2026-07-08.

---

## Verified-solid foundation (re-checked fresh, not gaps) ✅

- **Security boundary.** `platform/gateway/.../filters/JwtAuthFilter.java` strips client-supplied identity headers before stamping verified ones, fails closed, refuses to boot on a weak JWT secret; brute-force / rate-limit / CORS filters exist with tests. `tenant_id` is only ever taken from the verified JWT.
- **Messaging reliability.** `shared/common-service/.../KafkaEventLoop.java` does **manual offset commit with seek-back** and a real **dead-letter after `MAX_ATTEMPTS = 5`**; consumers gate readiness on a healthy loop (`KafkaConsumerRegistry`). Idempotent consumers dedupe via `processed_events`.
- **Money & concurrency.** `BigDecimal`/`NUMERIC` throughout; refund caps, layaway overpayment, and return-quantity caps are enforced inside locked transactions; inventory reserve/deduct locks rows `FOR UPDATE`; checkout is idempotent (replay returns the original).
- **Input & error hygiene.** `UuidParseExceptionMapper` turns malformed UUIDs into `400` (the old A3 500-bug), DTOs carry Bean Validation enforced via `Validations.validate`, sanitized 500s never leak SQL, cursors are opaque base64 keysets.
- **Contract.** Single response envelope, stable machine error codes, OpenAPI served at `/openapi` on all 14 modules, `/v1` gateway alias with RFC 8594 deprecation header on the unversioned form.

---

# NEW FINDINGS — integration & wiring (2026-07-08)

### N1 — notification-svc is a stub; nothing is ever actually sent 🔴 (blocking)
`notification-svc` contains a single consumer (`ShortageAlertConsumer`) that reacts to `StockBelowThreshold` by writing a `shortage_alerts` row. There is **no SMTP / mail / SMS / push / webhook code anywhere** in the module (its only tables are `shortage_alerts` + `processed_events`). Consequently **order confirmations, welcome emails, OTP delivery, low-stock emails, and receipt emails are never delivered** — the POS "email receipt" and storefront flows record intent but dispatch nothing.
- **Impact:** a customer-facing SaaS with no outbound comms; OTP-based flows and transactional email are non-functional.
- **Fix:** either (a) implement an outbound-channel abstraction (SMTP/SES + SMS/push providers) and consume `UserRegistered`, `OrderConfirmed`, `OrderFulfilled`, receipt, and `StockBelowThreshold` events idempotently; or (b) explicitly descope notifications in the PRD and remove the "email receipt" affordances from POS/storefront so the UI doesn't promise delivery it can't make.

### N2 — customer-svc consumes zero events → no loyalty automation ✅ RESOLVED (2026-07-08)
`customer-svc` had **no `messaging/` package** — it published events but subscribed to none, so loyalty accrued only through the manual `/{id}/loyalty/earn` endpoint.

> **Resolved.** customer-svc now consumes `shelfj.order.order-confirmed` and accrues loyalty for the buyer automatically:
> - **order-svc** enriches `OrderConfirmed` with `eventId` + `customerId` + `total` + `currency` (`Events.orderConfirmed`, both confirm paths — staff confirm and payment-captured). Emitted exactly once, at full payment.
> - **customer-svc** adds a `processed_events` table (V2 migration), an `OrderConfirmedConsumer` + `OrderConfirmedHandler`, and `CustomerService.accrueLoyaltyFromOrder` → `CustomerRepository.accrueFromOrderOnce`. Points = `total × shelfj.customer.loyalty.points-per-unit` (default 1, rounded down). The dedupe mark + accrual + `LoyaltyEarned` outbox event commit in **one transaction**, so a redelivered event accrues at most once. Guest orders (`customerId:null`) and unknown/anonymized customers are skipped without looping.
> - **Tests:** `OrderConfirmedHandlerTest` (4: real buyer / guest / legacy-missing-field / malformed) + `CustomerIT.loyaltyAccruesFromOrderOnceAndDedupesOnEventId` (Testcontainers: single accrual under redelivery). order-svc (50) + customer-svc (20) suites green.
>
> **Design decision — profile-on-register intentionally *not* implemented.** `customers` is per-tenant (`tenant_id NOT NULL`, required first/last name, `UNIQUE(tenant_id,email)`), but `UserRegistered` carries a **null tenant and no name** (a customer isn't bound to a tenant at registration). A profile is correctly created at transaction time via `POST /customers`. Auto-creating a tenant-less/nameless profile from `UserRegistered` would violate the schema; the honest fix is to leave profile creation where it is. If a global (cross-tenant) customer identity is ever wanted, that's a separate model change, tracked separately.

### N3 — store credit can't be used as tender at checkout ✅ RESOLVED (2026-07-08, store credit)
The POS tender screen already offered a "Store Credit" tender, but payment-svc **rejected `STORE_CREDIT` as an invalid method**, and the Flutter client was redeeming the balance **itself** (client-side orchestration) — so the redemption was neither server-authoritative nor reflected as a captured tender (the store-credit portion never accumulated into `paid_amount`).

> **Resolved (store credit).** Redemption is now server-side in the tender path:
> - **payment-svc** gains a `CustomerClient` (Consul-resolved, fault-tolerant) and accepts `STORE_CREDIT` as a tender method. `capture()` → `captureStoreCredit()` redeems the customer's balance via customer-svc **before** recording the tender (an insufficient balance → 422, so `paid_amount` is never inflated), then records a `STORE_CREDIT` `PaymentTender` → `PaymentCaptured` accumulates it like any other tender. Keyed idempotently on `"sc:"+orderId` (belt-and-suspenders with the customer-svc guard below), so a retried capture neither double-redeems nor double-tenders. The internal call stamps a trusted `X-Roles: CASHIER` for customer-svc's `AdminAuthorizationFilter`.
> - **customer-svc** `redeemStoreCredit` is now **idempotent per order** (a REDEEM already recorded for `(customer, order)` is a no-op), making the cross-service redeem retry-safe.
> - **Frontend** now sends `customerId`/`currency` on the `STORE_CREDIT` tender and **drops its own client-side redeem** — payment-svc is authoritative.
> - **Tests:** payment-svc unit (redeem-then-record, customerId required, idempotent replay) + customer-svc IT (per-order redeem idempotency). payment-svc (19) + customer-svc (21) green; `flutter analyze` clean; SpotBugs/PMD pass.
>
> **Follow-ups (tracked):** (1) **loyalty points as tender** — needs a points→currency redemption-rate decision (config); the manual `/loyalty/redeem` endpoint still exists. (2) **refund re-credit** — refunding a store-credit-tendered order (N5) should re-issue the credit; today the refund records a `STORE_CREDIT` refund tender but doesn't call customer-svc to re-credit.

### N4 — reporting-svc is inventory-only; no sales analytics exist ✅ RESOLVED (2026-07-08)
reporting-svc consumed only inventory/transfer events; there was no `sales_facts` and no revenue reporting despite `OrderConfirmed`/`PaymentRefunded` being on the bus.

> **Resolved.** Added a sales read-model (CQRS projection) driven by order/payment events:
> - **order-svc** enriches `OrderConfirmed` with `storeId` + `channel` (on top of the N2 `customerId`/`total`/`currency`), so a sale fact needs no callback.
> - **reporting-svc** adds `sales_facts` (V2), a `SalesEventConsumer` (own group `reporting-svc-sales`) + `SalesEventDispatcher`: `OrderConfirmed` → `recordSaleOnce` (naturally idempotent on the `(tenant, order)` PK via `ON CONFLICT DO NOTHING`); `PaymentRefunded` → `applySalesRefundOnce` (accumulates the refund, deduped on the event's `eventId`). So net = gross − refunded, and both manual and automatic (N5) refunds are reflected.
> - **Endpoints:** `GET /admin/reports/sales/summary` (gross/refunded/net + order count grouped by currency) and `/by-day` (daily buckets), with optional `from`/`to` (inclusive ISO dates), `storeId`, `channel` filters; tenant from JWT.
> - **Frontend:** a **Sales Revenue** tab in the admin Reports screen (`salesSummaryReportProvider` + `_SalesReport` table) — `flutter analyze` clean.
> - **Tests:** `SalesEventDispatcherTest` (routing/guards) + `ReportingIT` (gross/refunded/net with refund dedupe; sale idempotent on order id). reporting-svc (10) + order-svc (52) green; SpotBugs/PMD pass.
>
> Note: revenue is order-total based (per-line/product breakdown and tax splits are a later enrichment); `confirmed_at` uses the projection time (the event carries no timestamp), accurate to within processing latency.

### N5 — refunds neither propagate to the order nor auto-trigger ✅ RESOLVED (2026-07-08)
`payment-svc` consumed nothing (returns/cancels never auto-refunded) and `PaymentRefunded` had no consumer (a refunded order's status never changed).

> **Resolved.** The refund loop is now closed on both sides:
> - **payment-svc** gains an `OrderEventConsumer` on `shelfj.order.order-returned` + `shelfj.order.order-cancelled`. `OrderReturned` refunds the return amount **only for ORIGINAL-tender returns** (STORE_CREDIT/GIFT_CARD are settled elsewhere); `OrderCancelled` refunds whatever is still captured (unpaid pay-later cancels are a no-op — this also keeps the payment-failed→cancel saga path clean). `PaymentRepository.refundOrderOnce` is idempotent on the order event's `eventId`, caps at the remaining captured total, and **allocates across the order's captured tenders** so the per-tender cap holds for split-tender sales — all in one `FOR UPDATE` transaction. New `processed_events` table (V6).
> - **order-svc** enriches `PaymentRefunded` with `eventId` + `amount` and consumes it (extending the existing `PaymentEventConsumer`). `OrderRepository.applyRefundOnce` accumulates a new `orders.refunded_amount` column (V10, mirroring `paid_amount`) and flips a sold order to `PARTIALLY_REFUNDED` / `REFUNDED` (new status constants) once refunds reach the total — idempotent on `eventId`. The manual `/refunds` endpoint now propagates to order status too (same event).
> - `OrderReturned` gained `refundAmount`/`refundMethod`/`currency`; `OrderCancelled` gained `eventId` (existing inventory-svc hold-release consumer ignores the extra field).
> - **Tests:** `PaymentRefundIT` (Testcontainers: cap, dedupe, split-tender allocation, unpaid no-op) + `OrderEventHandlerTest` (ORIGINAL vs store-credit vs cancel vs malformed) on the payment side; `OrderIT.paymentRefundedFlipsOrderToPartiallyThenFullyRefunded` (partial→full + dedupe) + `EventsTest` on the order side. payment-svc (16) + order-svc (51) suites green.

### N6 — config-svc is deployed but unused ✅ RESOLVED (2026-07-08, wired)
`platform/config` was built, health-gated, and waited on, but no service fetched from it — dead infra that contradicted golden rule #5.

> **Resolved by wiring it in** (chosen over deletion, to make the documented centralized-config architecture real). Added `common-service`'s `ConfigServiceConfigSource` — a MicroProfile `ConfigSource` (registered via `META-INF/services/...ConfigSource`) that at startup fetches `GET /config/{service}/{profile}` from config-svc (authenticated with the shared `X-Config-Token`) and layers the returned values at **ordinal 150** — above the local `microprofile-config.properties` (100) but below env vars (300) / system properties (400). So config-svc overrides baked defaults while deploy-time env/secrets still win.
> - **Resilient + opt-in:** activates only when `shelfj.config.url` is set (compose sets it on all 12 services via the `*svc-env` anchor, profile `docker`). Unset → empty no-op, so local dev and every existing `@HelidonTest` run unchanged. A 404 (no config for the service) or an unreachable config-svc degrades to empty — the service still boots on local defaults, preserving "start in any order". Secrets never travel this path.
> - **Repo made usable:** `platform/config/.../config-repo/README.md` documents the file-naming + precedence model so ops can drop `{service}[-{profile}].properties` overrides in (empty today → every service 404s → identical behavior, but the plumbing is live).
> - **Tests:** `ConfigServiceConfigSourceTest` (no-op when unset, layers remote values + sends the token, 404 → empty, unreachable → empty) — common-service (20) green; iam-svc `@HelidonTest` (17) confirms clean boot with the SPI source on the classpath. SpotBugs + PMD gates pass.

### N7 — payment is client-choreographed; stranded PENDING orders are never cleaned up ✅ RESOLVED (2026-07-08)
The frontend calls `payment-svc` directly and `order-svc` only orchestrates quote + reserve, reacting to `PaymentCaptured` — so a client that died after order-create stranded the order in PENDING forever (only the stock hold was reclaimed).

> **Resolved.** Added `PendingOrderSweeper` (order-svc) — a daemon `ScheduledExecutorService` mirroring inventory-svc's `ReservationSweeper`. Every `shelfj.order.pending-sweeper.interval-seconds` (default 300) it cancels PENDING orders older than `…ttl-hours` (default 24) via `OrderService.sweepExpiredPendingOrders` → `OrderRepository.findExpiredPendingOrders` (cross-tenant scan) + the existing conditional `transitionOrderStatus` (PENDING→CANCELLED, so a concurrent confirm is safely skipped). Each cancellation emits `OrderCancelled`, which releases any remaining inventory hold and is a payment no-op (nothing captured). Enable flag + interval + TTL are configurable; batch capped at 200.
> - **Docs reconciled:** README §2 (Saga) and §12 (checkout flow) now describe the real choreography — order-svc orchestrates placement (quote + reserve) synchronously, payment is **client-initiated** against payment-svc, and order-svc confirms on the `PaymentCaptured` event rather than calling payment-svc. The sweeper is documented as the stranded-order backstop.
> - **Tests:** `OrderIT.sweeperCancelsExpiredPendingOrdersButNotConfirmedOnes` (expired PENDING → CANCELLED; CONFIRMED left untouched). order-svc (52) green.
>
> Note: this keeps the client-choreographed payment model (it works and split-tender POS relies on it) and adds the missing backstop, rather than moving capture into an order-svc-orchestrated step.

### N8 — many published events have no consumer 🟢 (observation)
Published-but-unconsumed today: `AccountingPeriod*`, `Kanban*`, `Serial*`, `Lot*`, `MoveOrder*`, `PriceChanged`, `PromotionActivated`, `Product*`, `VariantCreated`, `PurchaseOrderCreated`, `IntercompanyInvoiceRaised`, `StoreCredit*`, `Loyalty*`, `ZoneCreated`, `UserRoleGranted`, `OrderConfirmed`, `OrderVoided`, `PaymentRefunded`.
- **Impact:** mostly fine as future/audit hooks, but it means several "publish" side-effects are **end-to-end no-ops** and add outbox/Kafka traffic for nobody. Note the overlap: `OrderConfirmed` (N2/N4), `PaymentRefunded` (N5) and the loyalty events **should** be consumed.
- **Fix:** no action required for the genuine future-hooks; treat the N2/N4/N5 ones as the missing half of those findings; optionally document intent so they aren't mistaken for bugs.

### N9 — cart-svc consumes events without a processed_events dedupe table 🟢 (minor)
`cart-svc` has consumers (`OrderPlaced`, tenant/store status) but **no `processed_events` table**; it relies on natural idempotency (closing an already-closed cart / last-write-wins status is a no-op).
- **Impact:** correct today, but inconsistent with the pattern used everywhere else and fragile if a non-idempotent consumer is ever added.
- **Fix:** add the dedupe table when/if a non-idempotent consumer is introduced; otherwise document the reliance on natural idempotency.

### N10 — payment provider is still mocked 🟡 (carry-over, now explicit)
`payment-svc/.../api/PaymentResource.java:49` carries a "Hardening TODO: integrate a real payment provider." CASH/CARD/UPI/WALLET tenders are **recorded**, not captured through a PSP, and there is no webhook signature verification.
- **Impact:** no real money moves; fine for demo, blocking for launch.
- **Fix:** integrate a real PSP behind a provider interface; verify webhook signatures; preserve the existing idempotency-key handling.

---

# Carry-over open items (from the API / UI pass)

These remain genuinely incomplete (core landed, real scope left). Full implementation history for the *closed* items was removed in this revision.

| # | Item | Layer | Severity | State |
|---|---|---|---|---|
| A5 | Split god-files per aggregate (`InventoryRepository` ~3.6k lines, `ProductRepository` ~2.1k, bundled `Dtos.java`/`Domain.java`) | API | 🟠 | In progress — 2 repos extracted; pattern proven; bulk remaining |
| U2 | Server-side pagination + search across list screens | UI | 🔴 (at scale) | Orders list done (cursor infinite-scroll); customers/products/inventory-levels still fetch-all client-side (inventory-levels also needs a paginated backend endpoint) |
| U4 | Consume structured `error.code` on every screen | UI | 🟠 | `api_error.dart` helper + a few screens done; ~12 screens still `$e` snackbars |
| U5 | Accessibility (Semantics, non-colour status, ≥14px table text) | UI | 🟠 | Template slice done; app-wide roll-out pending |
| U6 | i18n string coverage (money/date formatting already locale-aware) | UI | 🟠 | Pipeline live (gen-l10n, en+pl); remaining screens still inline English; pl/ro/pa/ur/bn/gu/ar translations to commission |
| U7 | Design tokens (typography/spacing/semantic colours) | UI | 🟠 | Tokens established; magic-number sweep pending |
| U8 | Broaden frontend test coverage | UI | 🟠 | Harness + 11 test files live; main-screen golden/widget + provider integration tests pending |

---

# Prioritized remediation roadmap

| # | Item | Layer | Severity | Effort | Notes |
|---|---|---|---|---|---|
| N1 | Deliver notifications (or descope + de-promise in UI) | Backend | 🔴 | M–L | Touches OTP, order confirmations, receipts |
| ~~N2~~ | ~~Event-wire customer-svc (loyalty on order)~~ | Backend | ✅ | — | Done 2026-07-08 — OrderConfirmed consumer + idempotent accrual |
| ~~N5~~ | ~~Close the refund loop (auto-refund + status propagation)~~ | Backend | ✅ | — | Done 2026-07-08 — order-event refund consumer + `refunded_amount` status flip |
| ~~N6~~ | ~~Wire or delete config-svc~~ | Platform | ✅ | — | Done 2026-07-08 — wired via ConfigServiceConfigSource (ordinal 150, resilient) |
| N10 | Integrate a real payment PSP + webhook verification | Backend | 🔴 | L | Blocks real revenue |
| ~~N4~~ | ~~Sales projection + revenue reporting in reporting-svc~~ | Backend | ✅ | — | Done 2026-07-08 — sales_facts projection + summary/by-day endpoints + admin tab |
| ~~N3~~ | ~~Store credit redeemable as tender at checkout~~ | Backend | ✅ | — | Done 2026-07-08 — payment-svc CustomerClient + STORE_CREDIT tender (loyalty-as-tender follow-up) |
| ~~N7~~ | ~~Stranded-PENDING-order sweeper; reconcile saga docs~~ | Backend | ✅ | — | Done 2026-07-08 — PendingOrderSweeper + README saga reconciled |
| U2 | Server-side pagination roll-out | UI | 🔴@scale | L | +paginated `/admin/inventory/levels` endpoint |
| A5 | Continue god-file split | API | 🟠 | L | Per-aggregate repos extending `BaseOutboxRepository` |
| U4/U5/U6/U7/U8 | UI polish roll-outs | UI | 🟠 | M–L | Mechanical continuation of proven templates |
| N8/N9 | Prune/document orphan events; cart-svc dedupe if needed | Backend | 🟢 | S | Low priority |

---

## Reference
- Event loop / DLQ: `shared/common-service/src/main/java/com/shelfj/service/KafkaEventLoop.java`
- Security boundary: `platform/gateway/src/main/java/com/shelfj/gateway/filters/JwtAuthFilter.java`
- Checkout orchestration: `services/order-svc/src/main/java/com/shelfj/order/service/OrderService.java` + `client/`
- Stock deduction on order events: `services/inventory-svc/src/main/java/com/shelfj/inventory/messaging/OrderEventHandler.java`
- Notification stub: `services/notification-svc/src/main/java/com/shelfj/notification/messaging/ShortageAlertConsumer.java`
- Unused config service: `platform/config/` (served) vs. no consumer in `services/*`
