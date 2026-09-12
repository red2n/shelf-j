# Shelf-J — API Guide

> A business-capability tour of the entire API surface: what you can *do* with each part of it, not how it's implemented. Pair this with [README.md](../README.md) (the product tour these capabilities serve) and [docs/UI-GUIDE.md](UI-GUIDE.md) (the screens that call this API). For request/response schemas, data model, and event payload internals, read the actual code under `services/*/src/main/java/com/shelfj/*/api/` and `dto/` — this guide intentionally stays at the capability level so it doesn't drift out of sync with the code.

Every call goes through one public entry point and lands on one of 12 independent business capabilities behind it. Nothing else is reachable from outside the platform.

## Table of contents

- [The gateway (the one public door)](#the-gateway-the-one-public-door)
- [Conventions that apply everywhere](#platform-wide-conventions)
- [cart-svc — Cart](#cart-svc)
- [customer-svc — Customers, loyalty, store credit](#customer-svc)
- [iam-svc — Identity & access](#iam-svc)
- [inventory-svc — Stock, batches, planning](#inventory-svc)
- [notification-svc — Alerts](#notification-svc)
- [order-svc — Orders & checkout](#order-svc)
- [payment-svc — Payments & cash management](#payment-svc)
- [pricing-svc — Pricing, promotions, VAT](#pricing-svc)
- [product-svc — Catalog](#product-svc)
- [purchase-svc — Procurement](#purchase-svc)
- [reporting-svc — Analytics](#reporting-svc)
- [tenant-svc — Tenants, stores, zones, staff](#tenant-svc)

---

## The gateway (the one public door)

The gateway is the single public door to the whole platform — nothing else is internet-reachable. Every call comes in as `/api/{service}/{path...}` (an equivalent `/api/v1/{service}/{path...}` form exists too; the version prefix is currently informational, and the unversioned alias is marked `Deprecation`/`Link`-to-`/api/v1` on every response to steer clients forward). The gateway only proxies to the 12 declared business services (an explicit allowlist — `iam-svc, tenant-svc, product-svc, inventory-svc, pricing-svc, cart-svc, order-svc, payment-svc, purchase-svc, customer-svc, notification-svc, reporting-svc`); anything else, even if it happens to be registered in service discovery, is unreachable. It terminates and verifies the JWT, strips any client-supplied identity headers, and re-stamps `X-Tenant-Id` / `X-User-Id` / `X-User-Email` / `X-Roles` / `X-Store-Ids` from the verified token so every business service can trust those headers unconditionally (`ProxyResource.FORWARDED_HEADERS` is the one list of what reaches a service — a header stamped but not listed is dropped, which is how `X-Store-Ids` went missing for as long as it did, SJ-D46); it also forwards `Idempotency-Key` and the real `Content-Type` (so binary uploads like product images pass through intact) untouched.

Guest/customer storefront access is carved out by an explicit path whitelist keyed on an `X-Storefront-Tenant` header (catalog browse, price resolve, active promotions, per-store storefront config, stock availability, and — for a signed-in customer only — placing an order and paying online); everything else requires a verified staff or customer Bearer token.

Protective features: Redis-backed rate limiting (default 100 req/min per caller, so it holds across gateway replicas), brute-force login lockout (5 failed attempts → 15-minute block on `/auth/login`), a **card-data guard** (every body and query string is scanned for a payment card number — 15/16/19 digits, Luhn-valid, an issuer range issued at that length — and one found is answered `400 CARD_DATA_NOT_ACCEPTED` without being forwarded, echoed or logged; EAN-13/GTIN-14 barcodes, IMEIs and all-digit ids are deliberately not cards; with the log scrubber in every service, this is what makes the PCI DSS SAQ-A scope a fact rather than a design intention), a deny-by-default CORS policy (only explicitly configured browser origins get CORS headers), a per-upstream circuit breaker (opens after 5 consecutive connect/read failures for 10s and fails fast with a clean 503 instead of piling up timeouts), connect/read timeouts (2s/10s, surfaced as 502/504 rather than hangs), and a tenant-status gate that polls tenant-svc's `storefront/active` flag (cached 15s, fails open) so a suspended tenant's storefront stops serving without the gateway holding its own tenant data.

---

## Platform-wide conventions

These apply across (almost) every endpoint below and are called out per-service only where a service deviates from them:

- **Tenant scoping**: every request is scoped to the caller's tenant, taken from the gateway-verified identity (or, on public storefront paths, from the resolved storefront tenant) — never from a client-supplied body field.
- **Default-deny RBAC**: role model is `PLATFORM_ADMIN` (cross-tenant), `OWNER`, `MANAGER`, `STOREKEEPER`, `CASHIER`, `CUSTOMER`. The shared `AdminAuthorizationFilter` enforces four tiers: **(1)** most of the `/admin/...` subtree — every HTTP method, *including GETs* — requires a management role (`PLATFORM_ADMIN`/`OWNER`/`MANAGER`), with bootstrap/receipt exemptions (`POST /admin/tenant` during onboarding, `POST .../receipts`); **(2)** **staff-operable admin surfaces** require any staff role (`STOREKEEPER`/`CASHIER` included): `/admin/inventory/**` (warehouse), `/admin/cash/**` (till — resource layer may still demand MANAGER+ for close/drops), plus support GETs `GET /admin/tenant`, `GET /admin/stores…`, `GET /admin/products/variants/resolve`; **(3)** `POST .../refunds` and `POST .../void` require a management role even outside `/admin/`; **(4)** any other mutating request needs at least a staff role, except a small open allowlist (identity endpoints, onboarding, `/prices/resolve*`, `/orders`, `/cart*`, `/payments/online`, `/inventory/reservations*`); **(5)** **reads default-deny too** — since SJ-D11 a non-`/admin` GET needs at least a staff role unless it is on the curated open-read allowlist (storefront catalogue and config, availability, promotions, own cart, own orders, `/auth/me`, onboarding status). A GET added tomorrow ships closed. *(This paragraph previously ended "Non-`/admin` GETs outside the staff-admin list are not gated by this filter", which stopped being true when reads were closed and was found still standing during a live verification pass.)*
- **Pagination**: list endpoints are cursor-paginated — `?after=<meta.nextCursor>&limit=1-100` (default ~20).
- **Idempotency**: money- or stock-moving POSTs (place order, reserve/receive/adjust stock, record payment/refund/cash movement, goods receipt) accept an `Idempotency-Key` header so a retried POST is safe; placing an order requires one. Gift-card redemption has no key of its own and is instead idempotent on the order it is redeemed against. Together these are what let the POS replay a whole sale it captured offline.
- **Eventing**: domain events are written to each service's own outbox table in the same transaction as the state change and published to Kafka at-least-once; consumers dedupe on event id. No service calls another synchronously for these flows.

---

## cart-svc

### Cart (`/cart`)
Server-side pre-checkout cart, keyed by cart id, session id, or the caller's authenticated identity. Note: every call requires a verified token (cart paths are not on the gateway's public storefront whitelist, and no anonymous/guest token exists), so a tokenless guest cannot reach this service — the current Flutter storefront keeps its pre-checkout cart on-device and never calls cart-svc. The session-cart + merge endpoints exist for authenticated clients that want a server-side cart.

- `POST /cart` — create or fetch the caller's active cart.
- `GET /cart` — view the cart and its line items (identified by cart id, session id, or the caller's authenticated identity).
- `POST /cart/items` — add an item to the cart (increments quantity if the variant is already in it).
- `PUT /cart/items/{itemId}` — change the quantity of a cart line.
- `DELETE /cart/items/{itemId}` — remove a line from the cart.
- `POST /cart/merge` — merge a session-keyed cart into the authenticated customer's cart (post-login merge).

**Business rules**
- Cart is not on the gateway's public storefront whitelist — every call needs a verified token.
- Once an order is placed, the cart is asynchronously marked checked-out (via `OrderPlaced`), not synchronously cleared by the checkout call.

**Events**
- Consumes: `OrderPlaced` (marks the cart checked-out), `StoreStatusChanged`, `TenantStatusChanged` (local suspension gate).
- Publishes: none.

---

## customer-svc

### Profile & Addresses (`/customers`)
Customer master data for the storefront and POS.

- `POST /customers` — register a new customer profile.
- `GET /customers` — list customers.
- `GET /customers/lookup?email=&phone=` — find a customer by email or phone (POS/CRM lookup).
- `GET /customers/{id}` — get a customer profile.
- `PUT /customers/{id}` — update a customer profile.
- `DELETE /customers/{id}` — GDPR erasure/anonymize a customer (SJ-D43: also deletes their saved addresses and publishes `CustomerErased` so order-svc and notification-svc redact what they hold about them; the event names the login as well as the customer id, so the redaction reaches web orders — SJ-D44).
- `POST /customers/me` — the signed-in shopper's own record in this shop, created on first use from the token's login and email, or adopted from the unlinked record the till already held for that address (SJ-D44). Identity comes from the token only. order-svc calls this at checkout so an online order carries a customer id the shop can resolve; loyalty, the confirmation email and erasure all key on it.
- `GET /customers/me` — the same record, never created: 404 for a shopper who has not bought here.
- `GET /customers/me/export` · `GET /customers/{id}/export` — UK GDPR art.20 data export, for the shopper and for staff handling a request that arrived by phone or letter. One JSON document: profile, addresses, loyalty and store credit with both ledgers, marketing preferences with their evidence trail, and every order (fetched from order-svc by both ids). Fails closed: an unreachable order-svc is a 503 `EXPORT_ORDERS_UNAVAILABLE` and no file, never a partial export.

### Marketing consent (`/customers/me/marketing`, `/customers/{id}/marketing`, `/marketing/unsubscribe`)

PECR reg.22/23 and UK GDPR art.7(1). `marketing_preferences` is what is true now; the append-only `marketing_consent_log` is the evidence — who, when, how (`SIGNUP`, `CHECKOUT`, `PREFERENCE_CENTRE`, `STAFF`, `UNSUBSCRIBE_LINK`, `IMPORT`) and the wording shown. Four channels (`EMAIL`, `SMS`, `PHONE`, `POST`); basis `CONSENT` or `SOFT_OPT_IN`, kept apart because the law keeps them apart. **Silence is not consent:** a channel with no row is refused.

- `GET /customers/me/marketing` · `PUT /customers/me/marketing` — the shopper's preference centre. Channels left out of a `PUT` are untouched. Body: `{ channels: [{ channel, granted, basis? }], notice? }`.
- `GET /customers/{id}/marketing` · `PUT /customers/{id}/marketing` — the staff view, and consent taken over the counter; the acting staff member is recorded.
- `GET /customers/{id}/marketing/allowance?channel=` — whether one marketing message may be sent. Asked by notification-svc before every `MARKETING` send; answers no for an unknown channel, no preference, an opt-out or an erased record, and returns the per-send `unsubscribeToken` the message must carry when the answer is yes.
- `POST /marketing/unsubscribe` — the opt-out link. Public and unauthenticated by design (the token is the capability); stops every channel unless one is named; a second click is not an error. The gateway locks an address out after five wrong tokens (`429 TOKEN_LOCKED`), the same way it does wrong passwords.
- `POST /customers/{id}/addresses` — add a delivery/billing address.
- `GET /customers/{id}/addresses` — list a customer's addresses.
- `PUT /customers/{id}/addresses/{addressId}` — update an address.
- `DELETE /customers/{id}/addresses/{addressId}` — remove an address.

### Loyalty (`/customers/{id}/loyalty`)
- `GET /customers/{id}/loyalty` — view loyalty point balance.
- `POST /customers/{id}/loyalty/earn` — manually credit loyalty points.
- `POST /customers/{id}/loyalty/redeem` — redeem points (e.g. against a sale).
- `POST /customers/{id}/loyalty/adjust` — management correction to a balance.
- `GET /customers/{id}/loyalty/ledger` — loyalty transaction history.

### Store Credit (`/customers/{id}/store-credit`)
- `GET /customers/{id}/store-credit` — view store-credit balance (per currency).
- `POST /customers/{id}/store-credit/issue` — issue store credit (e.g. for a return).
- `POST /customers/{id}/store-credit/redeem` — spend store credit against a sale.

**Business rules**
- GDPR erasure is restricted to `PLATFORM_ADMIN`/`OWNER`/`MANAGER` — deliberately stricter than the shared default, since this path isn't under `/admin/` and would otherwise be open to any staff role.
- Loyalty earn/redeem/adjust and store-credit issue/redeem are deliberately left open to any staff role for normal POS checkout use.
- Loyalty is auto-accrued once per order from the `OrderConfirmed` event (deduped by event id); guest orders (no customer id) earn nothing.

**Events**
- Consumes: `OrderConfirmed` (auto-accrues loyalty points).
- Publishes: `CustomerRegistered` (also for a record created by `POST /customers/me`), `LoyaltyEarned`, `LoyaltyRedeemed`, `LoyaltyAdjusted`, `StoreCreditIssued`, `StoreCreditRedeemed`, `CustomerErased` (SJ-D43; ids only — `customerId` and, when linked, `loginId` — no email/phone, since the event outlives the erasure it announces).
- Calls: order-svc `GET /orders/export` (the sales half of a data export; fail-closed).

---

## iam-svc

### Authentication (`/auth`)
- `POST /auth/admin/staff-users` — find-or-create a staff account by email (a management action; the store-level role is granted separately via tenant-svc).
- `POST /auth/register` — self-register a new customer account.
- `POST /auth/login` — tenant staff / customer login.
- `POST /auth/platform-login` — platform-console login, kept deliberately separate so a `PLATFORM_ADMIN` credential never works on a store/POS login screen and vice versa.
- `POST /auth/refresh` — exchange a refresh token for a new access token.
- `POST /auth/logout` — revoke a refresh token.
- `PUT /auth/change-password` — change the caller's own password.
- `POST /auth/delete-account` — a customer deletes their own login (SJ-D43; requires the password again). Refused (403) for a staff account — that's removed by the business that employs its holder, not self-service.

### Bootstrap (`/bootstrap`)
- `POST /bootstrap/admin` — one-time creation of the first `PLATFORM_ADMIN` for a fresh deployment (refuses once one already exists; not JWT-gated by necessity).

### Identity (`/auth/me`)
- `GET /auth/me` — the caller's own identity, roles and profile, as verified by the gateway (the gateway forwards the token's identity, never the token).

### POS Sessions (`/auth/pos/sessions`)
- `POST /auth/pos/sessions` — start a cashier POS session.
- `PUT /auth/pos/sessions/{id}/activity` — heartbeat a session to keep it alive.
- `DELETE /auth/pos/sessions/{id}` — end a POS session.
- `GET /auth/pos/sessions` — list active POS sessions.
- `POST /auth/pos/sessions/sweep` — force-expire sessions idle past their configured timeout and revoke their tokens (platform-wide maintenance sweep).

**Business rules**
- The bootstrap admin endpoint is a self-disabling one-shot: it refuses if any `PLATFORM_ADMIN` already exists, so it's safe to leave enabled.
- The idle-session sweep ignores tenant scope by design (it's a platform-wide op), so it's restricted to `PLATFORM_ADMIN`.
- Provisioning a staff user only mints the identity; the actual store role is granted asynchronously when tenant-svc publishes `StaffAssigned`.

**Events**
- Consumes: `TenantCreated`, `StaffAssigned` (grants the store role), `StoreStatusChanged`, `TenantStatusChanged`.
- Publishes: `UserRegistered`, `AccountDeleted` (SJ-D43; id only, on self-service account deletion).

---

## inventory-svc

Nineteen resource classes covering the full stock/warehouse operations surface, grouped below by business sub-domain. All endpoints sit under `/admin/inventory` unless noted.

### Core Stock Operations
- `POST /admin/inventory/receive` — receive stock into a store/zone (manual goods-in), optionally against a batch number and expiry date.
- `POST /admin/inventory/receive/batch` — receive multiple lines in one call (partial success per line).
- `POST /admin/inventory/adjust` — adjust on-hand quantity up or down with a reason (shrinkage, damage, etc.).
- `GET /admin/inventory/levels` — on-hand stock levels per store/variant.
- `GET /admin/inventory/levels/summary` — aggregate KPI (total SKUs, low-stock count).
- `GET /admin/inventory/batches` — list stock batches/lots (filter by store, variant, material status).
- `GET /admin/inventory/batches/{id}` — get a batch.
- `PUT /admin/inventory/batches/{id}/material-status` — change a batch's QC/material status (e.g. quarantine → released).
- `GET /admin/inventory/movements` — stock movement/transaction history.
- `POST /admin/inventory/movements/purge` — bulk-delete movement history older than a given date (housekeeping).

### Lots, Serials & Batch Genealogy
- `POST /admin/inventory/lot-genealogy` — link a parent batch to a child batch (e.g. repack/kitting).
- `GET /admin/inventory/lot-genealogy/batch/{batchId}/ancestors` / `.../descendants` / `.../links` — trace a batch's lineage.
- `POST /admin/inventory/lots/split` — split a batch into two (spin off a partial quantity).
- `POST /admin/inventory/lots/merge` — merge one batch's quantity into another.
- `GET /admin/inventory/lots/{batchId}/actions` — audit history of split/merge actions on a batch.
- `GET /admin/inventory/batches/expiring` — batches expiring within N days (default 30) for a store.
- `PUT /admin/inventory/batches/{id}/grade` — set a batch's quality grade.
- `POST /admin/inventory/serials/register` — register serial numbers against a received batch (explicit list or auto-generated with a prefix).
- `GET /admin/inventory/serials` — list serial numbers (by store/variant/status).
- `GET /admin/inventory/serials/lookup?serial_no=` — find a serial by number.
- `GET /admin/inventory/serials/{id}` — get a serial's detail.
- `GET /admin/inventory/serials/{id}/history` — movement history for one serial.
- `PUT /admin/inventory/serials/{id}/status` — change a serial's status (sold, defective, in-stock, etc.).

### Cycle Counts & Physical Inventory
- `POST /admin/inventory/cycle-counts` — start a cycle count for a store, targeting selected ABC classes with a tolerance %.
- `GET /admin/inventory/cycle-counts` / `.../{id}` — list/get cycle counts and their lines.
- `POST /admin/inventory/cycle-counts/{id}/lines/{lineId}/count` — enter a counted quantity for one line.
- `POST /admin/inventory/cycle-counts/{id}/approve` — approve the count (auto-approves lines within tolerance, flags the rest for review).
- `POST /admin/inventory/cycle-counts/{id}/adjust` — post stock adjustments for the approved variances.
- `POST /admin/inventory/physical-inventories` — start a full physical inventory count for a store.
- `GET /admin/inventory/physical-inventories` / `.../{id}` — list/get physical inventories with their count tags.
- `POST /admin/inventory/physical-inventories/{id}/tags` — add a count tag (system-quantity snapshot) for a variant/zone.
- `POST /admin/inventory/physical-inventories/{id}/tags/{tagId}/count` — record the physically-counted quantity for a tag.
- `POST /admin/inventory/physical-inventories/{id}/complete` — close out the physical inventory.

### ABC Analysis
- `POST /admin/inventory/abc/compile` — run an ABC classification pass for a store (by revenue/velocity criteria and A/AB thresholds).
- `GET /admin/inventory/abc/assignments` / `.../{storeId}/{variantId}` — list/get variant → ABC class assignments.

### Planning, Reorder & Demand
- `POST /admin/inventory/thresholds` / `GET .../thresholds` — set/list a reorder threshold + max qty per store/variant.
- `POST /admin/inventory/planning/run` — run the min/max replenishment plan for a store, generating suggestions.
- `GET /admin/inventory/planning/suggestions` / `PUT .../{id}/status` — list/resolve replenishment suggestions.
- `PUT /admin/inventory/rop-plans` / `GET` / `GET .../by-variant` — set/list/get reorder-point & EOQ parameters (lead time, ordering/holding cost, unit cost).
- `POST /admin/inventory/rop-plans/compute` — recompute reorder points/EOQ for a store.
- `PUT /admin/inventory/rop-plans/{id}/order-modifiers` — set min/max order qty and lot-multiplier constraints on a ROP plan.
- `POST /admin/inventory/safety-stock` / `GET` / `GET .../{storeId}/{variantId}` — set/list/get safety-stock parameters (method, lead time, service level).
- `POST /admin/inventory/safety-stock/compute` — recompute safety-stock levels.
- `PUT /admin/inventory/lots/{batchId}/uom-conversions` / `GET` — define/list lot-specific unit-of-measure conversions.
- `PUT /admin/inventory/par-levels` / `GET` — set/list PAR (periodic automatic replenishment) levels.
- `POST /admin/inventory/demand/aggregate` — aggregate historical sales movements into demand buckets (day/week/month).
- `GET /admin/inventory/demand/history` — list demand-history buckets.

### Transfers & Move Orders
- `POST /admin/inventory/transfers` — create an inter-store transfer order.
- `GET /admin/inventory/transfers` / `.../{id}` — list/get transfer orders with lines.
- `POST /admin/inventory/transfers/{id}/ship` — mark a transfer shipped from the source store.
- `POST /admin/inventory/transfers/{id}/receive` — receive a transfer at the destination store.
- `POST /admin/inventory/transfers/{id}/cancel` — cancel a transfer order.
- `POST /admin/inventory/move-orders` — create an intra-store zone-to-zone move order.
- `GET /admin/inventory/move-orders` / `.../{id}` — list/get move orders with lines.
- `POST /admin/inventory/move-orders/{id}/pick` — pick/complete a move order.
- `POST /admin/inventory/move-orders/{id}/cancel` — cancel a move order.

### Reservations (`/inventory/reservations`)
Called by order-svc during checkout to hold stock before it's actually deducted.
- `POST /inventory/reservations` — place a time-limited stock hold for an order line.
- `GET /inventory/reservations` / `.../{id}` — list/get reservations.
- `POST /inventory/reservations/{id}/consume` — convert a hold into an actual deduction.
- `POST /inventory/reservations/{id}/release` — release a hold back to available stock.
- `POST /inventory/reservations/batch` — reserve stock for multiple lines in one call (partial success reported).

### Kanban Replenishment
- `POST /admin/inventory/kanban-cards` — create a kanban card for a store/variant (optionally sourced from another store).
- `GET /admin/inventory/kanban-cards` / `.../{id}` — list/get kanban cards.
- `POST /admin/inventory/kanban-cards/{id}/trigger` — trigger a card's replenishment signal.
- `POST /admin/inventory/kanban-cards/{id}/replenish` — mark a triggered card replenished.
- `PUT /admin/inventory/kanban-cards/{id}/order-modifiers` — set order-modifier constraints on a card.

### Costing & Accounting Periods
- `PUT /admin/inventory/costing-methods` / `GET` / `GET .../by-variant` — set/list/get the costing method (e.g. FIFO/average) per store/variant.
- `POST /admin/inventory/accounting-periods` — open an inventory accounting period.
- `GET /admin/inventory/accounting-periods` / `.../{id}` — list/get accounting periods.
- `POST /admin/inventory/accounting-periods/{id}/close` — close a period (locks it from further postings).

### Reference Data & Picking Rules
- `POST /admin/inventory/reason-codes` / `GET` / `.../{id}/activate|deactivate` — manage stock-adjustment reason codes.
- `POST /admin/inventory/source-types` / `GET` / `.../{id}/activate|deactivate` — manage movement source types.
- `PUT /admin/inventory/zone-gl-mappings` / `GET` — map a store/zone to a general-ledger nominal code.
- `POST /admin/inventory/picking-rules` / `GET` / `.../{id}` / `DELETE .../{id}` — define/list/get/deactivate picking strategy rules (e.g. FEFO/FIFO).
- `PUT /admin/inventory/picking-rules/{id}/zone-priorities` / `GET` — set/list the zone pick-priority order for a rule.
- `POST /admin/inventory/picking-rule-assignments` / `GET` / `DELETE .../{id}` — assign/list/remove a picking rule's scope (store/category/variant).
- `GET /admin/inventory/picking-rules/resolve` — resolve which picking rule/zone order applies to a given store + variant.

### Reports (`/admin/inventory/reports`)
These are answered from inventory-svc's own tables rather than from reporting-svc, whose projections carry neither the reason code, the actor, nor any cost. See [reporting-api-gap-analysis.md](reporting-api-gap-analysis.md) for why each landed where it did.
- `GET /admin/inventory/reports/low-stock?storeId&limit` — items below their **own** reorder level, live, with `signal` naming which of threshold / safety stock / reorder point bound the row. Items that have run out entirely are included: they have no batch rows at all, and they are the most urgent line in the report.
- `GET /admin/inventory/reports/valuation?storeId&groupBy=STORE|VARIANT&limit` — what the holding is worth on its configured FIFO or AVERAGE basis. Stock carrying no cost comes back as `unvaluedQty` rather than valued at zero, which would understate a balance-sheet figure.
- `GET /admin/inventory/reports/shrinkage?storeId&from&to&groupBy=REASON|ACTOR|STORE` — write-offs over a period. Losses and finds stay on separate columns: a store that wrote off 100 units and found 100 others is not one that did nothing. `.../shrinkage/by-variant` drills a summary row down to what was actually written off.
- `GET /admin/inventory/reports/stock-turn?from&to&storeId&groupBy=STORE|VARIANT&limit` — cost of goods sold over a window against the average value held to produce it, plus `turnoverRatio` and `daysOnHand`. **`from` and `to` are required** — a turnover ratio has no meaning without a window, and `daysOnHand` divides by its length; both take a full ISO-8601 instant, not a bare date. COGS is taken from the cost price of the batches each sale actually drew down, so a historical window is answered with the costs of the day. Two fields say when a figure should not be trusted: `historyComplete` is false when the movement ledger was purged past the window's start, making opening values a floor; `uncostedSaleQty` reports quantity sold out of batches with no cost price, excluded from COGS rather than costed at zero. `turnoverRatio` and `daysOnHand` are **null**, not zero, when there was no stock to turn.
- `GET /admin/inventory/reports/dead-stock?storeId&asOf&groupBy=BUCKET|STORE|VARIANT&limit` — stock on hand aged into the 0-30 / 31-60 / 61-90 / 91-180 / 180+ ladder. Age runs from the last **sale** of that item at that store, not from receipt: stock that arrived two years ago and sold this morning is not dead. Where nothing has ever sold, age runs from the oldest remaining batch's arrival and `neverSold` says so. `asOf` pins the instant ages are measured from, so a re-run against the same date gives the same answer.

### Food Safety Checks (`/admin/inventory/food-safety` staff · `/admin/food-safety` management)
Temperature monitoring (the cold chain) and HACCP due-diligence records, on one model: a temperature reading is a check with a number and limits, an opening check is one with a yes or no. Legal basis (England): Food Safety and Hygiene (England) Regulations 2013 Sch.4 (chilled ≤ 8 °C, hot-held ≥ 63 °C), Reg (EC) 852/2004 art.5 (HACCP-based procedures), Food Safety Act 1990 s.21 (these records are the due-diligence defence).

*Store staff* (any staff role, by path — a storekeeper or a deli cashier takes the reading):
- `GET /admin/inventory/food-safety/points?storeId&includeInactive` — a store's monitoring points, each with its check type, limits, last check, `dueStatus` (`OK`, `DUE` for the last quarter of the interval, `OVERDUE`) and `openFailures` (failures with no corrective action).
- `GET /admin/inventory/food-safety/check-types` — the platform's reference types (chilled/frozen storage, hot holding, cooking core, chilled/frozen delivery, opening/closing checks, cleaning, pest check) and the tenant's own. `statutory` is true only where the limit is law rather than FSA guidance.
- `POST /admin/inventory/food-safety/records` — record a check: `value` (°C) for a temperature check, `passed` for a pass/fail check, never both. **The server decides the result** and stores the limits it judged against, so a later change to a limit never rewrites a past record. A reading is judged at the precision it is stored at: more than two decimal places is refused (`FOOD_SAFETY_TOO_PRECISE`) rather than rounded, because `NUMERIC(6,2)` would otherwise store 8.004 as 8.00 on a record judged a failure against 8.00. Honours `Idempotency-Key` (201 first time, 200 with the same record on replay). A store-restricted caller may only record at their own stores (`STORE_ACCESS_DENIED`). A switched-off point takes no checks (`FOOD_SAFETY_POINT_INACTIVE`).
- `GET /admin/inventory/food-safety/records?storeId&pointId&result&openOnly&from&to&after&limit` — the diary, newest first, cursor-paginated; `from` inclusive, `to` exclusive.
- `GET /admin/inventory/food-safety/records/{id}` — one record with its corrective actions.
- `POST /admin/inventory/food-safety/records/{id}/corrective-actions` — what was done (`action`) and what happened to the food (`foodDisposition`: `NONE|MOVED|DISCARDED|REHEATED|RECOOKED|OTHER`). A failure stays open until one is recorded; more than one may be. 409 `FOOD_SAFETY_RECORD_PASSED` on a check that passed.

*Management* (under `/admin/` outside the warehouse subtree, so gated to OWNER/MANAGER by path):
- `POST /admin/food-safety/points` · `PUT /admin/food-safety/points/{id}` — a chiller, freezer, hot cabinet, goods-in bay or checklist at a store. Limits default to the check type's and **may only be stricter** — 422 `FOOD_SAFETY_LIMIT_LAXER_THAN_TYPE` for a chiller set to 10 °C when the law says 8. A check is judged against the tighter of the point's limits and its type's current ones, so tightening a type later still binds older points. Store and check type are fixed once created.
- `POST /admin/food-safety/points/{id}/deactivate` · `/activate` — with a required `reason`, kept in an append-only trail; 409 `FOOD_SAFETY_ALREADY_IN_STATE`.
- `POST /admin/food-safety/check-types` · `PUT /admin/food-safety/check-types/{id}` — the tenant's own types. Platform types are read-only (409 `FOOD_SAFETY_TYPE_READ_ONLY`), and a tenant's own type is never marked statutory.
- `POST /admin/food-safety/reviews` · `GET /admin/food-safety/reviews?storeId&after&limit` — a manager's periodic sign-off (the HACCP verification step), storing the record, failure and open-failure counts as they stood when signed.

Records, corrective actions, reviews and point switches are append-only. A background sweep (every 15 minutes, `shelfj.inventory.food-safety.overdue-sweeper.*`) raises `FoodSafetyCheckOverdue` once per missed due time.

### Withdrawals and Recalls (`/admin/inventory/recalls` staff · `/admin/recalls` management)
A withdrawal takes stock off sale; a recall also tells the customers who may have bought it. Legal basis: Reg (EC) 178/2002 art.19 (retained), and the FSA's guidance on traceability, withdrawals and recalls. A recall's scope is one or more lines, each a variant optionally narrowed to a lot (`batchNo`) and a best-before or use-by range; a line naming neither covers every pack.

*Management* (gated to OWNER/MANAGER by path):
- `POST /admin/recalls` — open one: `reference` (the notice's, unique per tenant, case-insensitive — 409 `RECALL_REFERENCE_TAKEN`, so a retried open never opens a second recall), `kind` (`WITHDRAWAL|RECALL`), `hazard`, `reason`, `customerNotice` (**required for a RECALL**, 400 `RECALL_NOTICE_REQUIRED`), `source` (`SUPPLIER|FSA|FSS|INTERNAL|OTHER`) and `sourceReference`, and `items` (1 to 100 lines). **Every batch in scope, at every store, is set to `RECALLED` in the same transaction**, so no sale or reservation can draw on it between the recall existing and the stock being held. A batch whose lot or date is not known is held too, as `LOT_UNKNOWN` or `DATE_UNKNOWN`: a pack nobody can rule out is withdrawn. A batch number this service writes itself (`ADJ`, `CC-…`, `MO-…`, `TO-…`, `RET-…`) names a movement, not a lot, and counts as unknown. Publishes `RecallOpened` naming the stores whose stock was held.
- `POST /admin/recalls/{id}/close` — with optional close-out `notes`. **Refused with 409 `RECALL_STORES_OUTSTANDING` while any store still holds recalled stock with no final disposition**; `error.details` lists those store ids.
- `POST /admin/recalls/{id}/cancel` — a recall opened in error, with a required `reason`. Puts its stock back to the status it had before any recall held it, unless another open recall still holds the batch. 409 `RECALL_ALREADY_ACTIONED` once any store has returned or destroyed stock under it — that stock has left the books.

*Store staff* (any staff role, by path):
- `GET /admin/inventory/recalls?status&after&limit` — newest first, with each recall's scope-line count, stores affected, stores still to act and quantity held.
- `GET /admin/inventory/recalls/active` — every scope line of every open recall. **The till keeps this list and checks each item against it**, so a scan never waits on the network.
- `GET /admin/inventory/recalls/{id}` — scope, every batch held (with `match`, quantity at quarantine, remaining, `quarantinedOn` `OPEN|ARRIVAL`, and any release), every store action, and per-store progress.
- `POST /admin/inventory/recalls/{id}/stores/{storeId}/actions` — what a store found and did: `qtyFound`, `disposition` (`HELD_FOR_COLLECTION|RETURNED_TO_SUPPLIER|DESTROYED`), `noticeDisplayed`, `notes`. The response carries `systemQty`, what the system held off sale there, kept beside the count. **`RETURNED_TO_SUPPLIER` or `DESTROYED` takes the store's held stock off the books** — an `ADJUST` movement per batch with reference `RECALL` and reason `RECALL_WITHDRAWAL`, attributed to the caller, and `StockAdjusted` per variant. A store-restricted caller acts only at their own stores (`STORE_ACCESS_DENIED`).
- `POST /admin/inventory/recalls/{id}/batches/{batchId}/release` — a pack checked and found not to be affected, with a required `reason`. Only an uncertain match can be released: 409 `RECALL_BATCH_IN_SCOPE` for a batch whose lot and dates are in scope, `RECALL_BATCH_ALREADY_RELEASED` for a second release.

**Stock that arrives under an open recall is held as it arrives.** Every batch insert — a delivery, a transfer, a return, a count, an adjustment — checks the open recalls for its variant in the same transaction, so a delivery of the recalled lot the next day is never on sale for a moment; the receive response already says `RECALLED`. Scope lines, held batches, releases and store actions are append-only; only the recall's own status changes.

### Public Storefront Availability (`/inventory/availability`)
- `GET /inventory/availability` — public in-stock (yes/no) flag per variant for a store; no quantities are ever exposed.

**Business rules**
- `Idempotency-Key` is honored on receive/adjust/reserve so a retried POS or integration call can't double-move stock.
- Reservations follow hold → consume (fulfilled) or release (cancelled/expired); this is the checkout-time hold order-svc takes before stock is truly deducted.
- Transfer orders (inter-store) run create → ship → receive/cancel; move orders (intra-store, zone-to-zone) run create → pick → cancel.
- Cycle counts auto-approve variances within a configurable tolerance %; anything outside tolerance is flagged for manual adjustment.
- Kanban cards and ROP/EOQ plans both carry shared order-modifier fields (min/max order qty, lot multiplier) that constrain any suggested replenishment quantity.
- Public storefront endpoints expose only availability, never on-hand quantities.

**Events**
- Consumes: `GoodsReceived` (from purchase-svc), `OrderFulfilled`, `OrderReturned`, `OrderCancelled`, `OrderVoided` (from order-svc — deduct / restock / release hold / put back a voided till sale). A voided sale is received back as a `RECEIVE` movement with reference type `VOID` — distinct from a return's `RETURN` — because a sale voided after the money is taken and a customer return are different loss-prevention signals. It is received rather than reversed because the void and the fulfil arrive on different topics in either order, and a receipt and a deduction net to the same stock whichever lands first. Stock turn, demand history and dead stock all exclude a sale whose order was voided. None of them nets it: the receipt goes into a new return batch, stock turn sums per batch, and a netted sale would leave its cost in one group and a negative sale in another. An `OrderVoided` from before this change has no lines and is skipped.
- Publishes: `StockReceived`, `StockReserved`, `StockReleased`, `StockDeducted`, `StockAdjusted`, `StockBelowThreshold`, `ReplenishmentSuggested`, `ReplenishmentResolved`, `SerialsRegistered`, `SerialStatusChanged`, `MaterialStatusChanged`, `TransferOrderShipped`, `TransferOrderReceived`, `TransferOrderCancelled`, `MoveOrderCompleted`, `MoveOrderCancelled`, `CycleCountAdjusted`, `PhysicalInventoryCreated`, `PhysicalInventoryCompleted`, `CostingMethodUpdated`, `AccountingPeriodOpened`, `AccountingPeriodClosed`, `KanbanTriggered`, `KanbanReplenished`, `KanbanCreated`, `RopPlanUpdated`, `RopComputed`, `LotSplit`, `LotMerge`, `FoodSafetyCheckFailed` (in the same transaction as the failing record; ids, reading and limits only), `FoodSafetyCheckOverdue`, `RecallOpened` (reference, kind, hazard and the ids of the stores whose stock was held; never the free-text reason or notice).

---

## notification-svc

### Notifications (`/admin/notifications` + send)
Fan-in from Kafka events, plus a staff send path for POS receipts etc.
- `GET /admin/notifications/shortage-alerts` — list low-stock shortage alerts (by store or variant).
- `GET /admin/notifications` — in-app notification feed (welcome, order-confirmation, POS receipt, etc.), newest first.
- `POST /notifications/send` — staff-triggered send (order-svc uses this for EMAIL receipts). Body: `recipient`, `subject`, `body`, optional `type`/`eventId`/`customerId`/`category`. `category: MARKETING` (PECR reg.22) must name the customer and is sent only after customer-svc's `/marketing/allowance` says yes — otherwise `409 MARKETING_CONSENT_MISSING`, and also when the answer cannot be had (fail-closed: sending without provable consent is the offence). An allowed marketing message has the opt-out link appended by notification-svc itself (reg.23).

**Business rules**
- Channel is selected by `shelfj.notification.channel`: `app` (default, in-app only), `email`/`smtp` (SMTP **plus** in-app via a composite channel so the feed still fills when email is on), or `mqtt` (device-facing push — POS terminals, kiosk/back-store displays, platform console — **plus** in-app; topic `shelfj/notifications/{tenantId}/{recipient}`).
- MQTT auth: every client (including this service's own publisher connection) presents a shelfj platform JWT as the MQTT password; the broker (EMQX) verifies it and ties the connecting username to the JWT's `tenant` claim, then ACL-scopes reads to that tenant's own topic subtree — see `infra/emqx.conf` / `infra/emqx-acl.conf`.

**Events**
- Consumes: `OrderConfirmed` (order-confirmation notice), `StockBelowThreshold` (shortage alert), `FoodSafetyCheckFailed`, `FoodSafetyCheckOverdue` (store alerts, addressed to the store's devices like a shortage alert, once per event), `RecallOpened` (one alert per store whose stock was held, each deduplicated on an id derived from the event and the store), `UserRegistered` (welcome/registration notice), `CustomerErased` (SJ-D43; redacts the recipient/subject/body of that shop's messages to the customer), `AccountDeleted` (SJ-D43; redacts the platform's own messages to the deleted account, e.g. `WELCOME` — a shop's own messages are redacted by its own `CustomerErased` instead).
- Publishes: none.

---

## order-svc

### Order Lifecycle (`/orders`)
- `GET /orders` — list orders for the tenant (filter by store/channel/status/date range).
- `GET /orders/mine` — the signed-in customer's own order history (never another customer's, never the tenant's full book).
- Order lines (`items[].weighingInstrumentId`) — for a line sold by weight, the certified instrument the reading came from, from tenant-svc's register; the till refuses to sell by weight from one that is not certified, and the line records which it was, so an inspector can go from a receipt to a certificate. Null for a line sold by the each.
- `POST /pos/age-checks` — the due-diligence record behind an age-restricted sale (Licensing Act 2003 s.139 and its equivalents: the defence is "all reasonable precautions", and a precaution nobody can show was taken is none). One append-only entry per check the till made, pass or refusal, carrying the rule that applied at that moment (category, minimum age, country, whether it was store policy), the cashier from the token, and — required on a refusal — why: `UNDER_AGE`, `NO_ID`, `ID_REJECTED`, `PROXY_SALE`, `OTHER`; optionally what was shown on a pass (`PASSPORT`, `DRIVING_LICENCE`, `PASS_CARD`, …). Any staff role, at a store the cashier is assigned to. A refusal with no reason, a pass with one, or a value outside the vocabulary is a 400.
- `GET /admin/pos/age-checks?store=&outcome=&from=&to=&after=&limit=` · `GET /admin/pos/age-checks/summary?store=&from=&to=` — the register and its counts (total, passed, refused, refusals by reason, checks by category): what a licensing officer asks for first. Management-only.
- `GET /orders/export?customer=&login=` — every order one person placed here, with lines, for a data export (art.20). Staff-only, and read service-to-service by customer-svc, which assembles the whole document. Both ids are accepted because a person has both: an online sale is filed under their login and a till sale under the shop's customer record (SJ-D44). Neither id is a 400; a shopper token is a 403 — the read allowlist no longer takes a literal such as `export` for an order id, and the resource requires a staff role itself.
- `POST /orders` — place a new order (POS or ONLINE channel); requires an `Idempotency-Key`.
- `POST /pos/log/orders/{orderId}` — journal a completed POS sale to the transaction log. **Staff-reachable** (CASHIER/MANAGER/OWNER) and idempotent on the order, so a retry or a replayed offline sale returns the existing entry. This write used to sit under `/admin/pos-log`, which is management-gated — the cashier who took the sale could not journal it, so nothing ever did and the table was empty for the life of the product. The read side stays at `GET /admin/pos-log`.
- `GET /admin/reports/exceptions?from&to&storeId&groupBy=ACTOR|STORE` — staff exception report: discounts, voids and no-sale drawer opens per staff member or store, with journalled sales as the denominator. Check `journalCoverage` before reading any rate; when false, nothing journalled a sale in the period and the counts have nothing to divide by.
- `GET /admin/reports/sales-by-hour?from&to&storeId&channel&tz` — takings bucketed by hour of the trading day, so a manager can staff to the actual peak. `tz` takes an IANA zone name (`Europe/London`) or a bare ISO offset (`+05:30`); without it the hours are counted in UTC, which puts a shop outside UTC at the wrong time of day. Only CONFIRMED and FULFILLED orders count. Hours with no trade are absent rather than returned as zero.
- `GET /admin/reports/sales-by-staff?from&to&storeId&limit` — takings per cashier with average basket and discount rate, biggest taker first. Read from the POS transaction journal, so **in-store only** — an online order has no cashier, and these totals will not reconcile to the sales summary for that reason. `UNATTRIBUTED` buckets journal entries naming nobody.
- `GET /orders/{id}` — get an order with its line items.
- `POST /orders/{id}/confirm` — confirm an order (e.g. once payment settles). A **till sale** — channel `POS`, fulfilment `INSTORE` or `PICKUP` — is confirmed *and* handed over in the same transaction, emitting `OrderFulfilled` so its stock is deducted.
- `POST /orders/{id}/cancel` — cancel an order pre-fulfilment.
- `POST /orders/{id}/fulfil` — hand an order over, or part of it (SJ-D35). Without a body, everything still outstanding goes — for an untouched order the plain all-or-nothing fulfilment this always was. With `{lines:[{variantId, qty}]}`, only those quantities go now: each line keeps a cumulative `fulfilledQty`, the order is `PARTIALLY_FULFILLED` until every line is complete and then `FULFILLED`, the status history records "part-fulfilled: 4 of 5 units handed over", and each call emits one `OrderFulfilled` carrying only that call's quantities, so inventory-svc deducts what left the store now and nothing twice (its per-line dedupe is keyed by event, so several events per order are fine; a checkout hold that does not match a partial line is released and the deduction taken directly). Refused: more than is outstanding (`409 ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING`, saying how much is), a variant not on the order (`400 ORDER_FULFIL_LINE_UNKNOWN`), a zero or negative quantity (`400`), an order that is not `CONFIRMED`/`PARTIALLY_FULFILLED` (`409 ORDER_NOT_FULFILLABLE`), a caller without access to the order's store (`403`). A part-fulfilled order cannot be cancelled (`409 ORDER_PARTLY_FULFILLED` — return the goods or hand over the rest); a short close of the remainder is not built. For online and delivery orders; a till sale never needs it, because it is handed over whole when it is paid for.
- `GET /orders/{id}/history` — order status history/audit trail.
- `POST /orders/{id}/void` — void a completed POS sale (post-fulfilment correction, distinct from cancel). **Puts the stock back**: `OrderVoided` carries `eventId`, `storeId` and the lines to restock — each line net of anything already returned, and empty when the sale was never handed over, so nothing that was not deducted is restocked. Decided under a row lock on the order, and "handed over" is read from the append-only status history rather than the current status, which a refund would have moved on.
- `POST /orders/{id}/returns` / `GET /orders/{id}/returns` — create/list returns against an order (full or partial). Only goods that were handed over come back: the order must be `FULFILLED` or `PARTIALLY_REFUNDED`, otherwise `409 ORDER_CANNOT_RETURN` (cancel a `PENDING`/`CONFIRMED` order instead); returning more than was **handed over** (not ordered — on a part-fulfilled order the rest never left the store) is `409 RETURN_QTY_EXCEEDS_PURCHASED`; a `PARTIALLY_FULFILLED` order can be returned against up to what it has handed over.

### POS Operations
- `POST /pos/parked-sales`, `GET /pos/parked-sales`, `GET /pos/parked-sales/{id}`, `DELETE /pos/parked-sales/{id}` — park (suspend), list, get, or cancel an in-progress POS sale so a cashier can serve another customer and resume later.
- `POST /pos/no-sale` — log a no-sale/open-drawer event.
- `POST /admin/pos-log/orders/{orderId}` — record the POSLog transaction-journal entry for a fulfilled POS order.
- `GET /admin/pos-log`, `GET /admin/pos-log/orders/{orderId}` — list the POS transaction journal, tenant-wide or per order.
- `GET /admin/pos/stock-positions` — live (eventually-consistent) on-hand snapshot for POS screens, fed asynchronously from inventory-svc events.
- `POST /admin/orders/{orderId}/receipts`, `GET /admin/orders/{orderId}/receipts` — log/list receipt generation events (print or email; the frontend renders the receipt itself).

### Alternative Sale Types
- `POST /gift-cards` — issue a gift card. `GET /gift-cards/{code}` — look one up. `POST /gift-cards/{code}/reload` — top up balance. `POST /gift-cards/{code}/redeem` — spend from balance; **idempotent per `orderId`** (a repeat redemption against the same order returns the card unchanged rather than deducting twice, matching how a `STORE_CREDIT` tender is keyed in payment-svc). A redemption sent without an `orderId` is a manual adjustment and is not deduplicated. `GET /gift-cards/{code}/transactions` — transaction history.
- `POST /layaways` — create a layaway (reserve goods against installment deposits). `GET /layaways/{id}` — get with items/deposits. `POST /layaways/{id}/deposits` — record a deposit. `POST /layaways/{id}/complete` — final payment received, hand over goods. `POST /layaways/{id}/cancel` — cancel.
- `POST /admin/special-orders` — create a special order (customer order for future delivery, no immediate stock deduction). `GET /admin/special-orders`, `GET .../{id}` — list/get. `POST .../{id}/confirm`, `.../fulfil`, `.../cancel` — lifecycle actions.

**Business rules**
- Placing an order requires an `Idempotency-Key` (rejected with 400 otherwise) — the one hard requirement across the whole surface.
- `POS` channel orders require a `CASHIER`/`MANAGER`/`OWNER` role; `ONLINE` orders need no staff role — but the gateway only forwards order placement for a verified, signed-in customer token (there is no anonymous guest checkout).
- Special orders deliberately skip immediate inventory deduction, unlike regular POS/online orders.
- Void, cancel, and return are three distinct lifecycle actions: void corrects a completed sale, cancel stops an unfulfilled order, return is a post-sale reversal.
- **A till sale is handed over the moment it is paid for (SJ-D40).** The capture that completes it moves it `PENDING → CONFIRMED → FULFILLED` in one transaction and writes `OrderFulfilled` alongside `OrderConfirmed`. Before this, inventory-svc deducted stock only on `OrderFulfilled` and nothing ever fulfilled a till sale — the till places the order, payment capture confirmed it, and there it stopped. Stock moved only if a manager later opened each sale and clicked *Mark fulfilled*. `PICKUP` counts as a till sale as well as `INSTORE` because the till sent `PICKUP` for every tendered sale until the fix, and sales already queued offline replay with it. `DELIVERY` is not: goods leaving on a van are handed over when they arrive. A partial tender or a redelivered capture writes nothing, so a sale is fulfilled — and deducted — exactly once.
- The POS stock-position projection is explicitly documented as eventually consistent and must not be used to make reservation decisions.

- **A customer this shop erased keeps their delivery details only until an open order finishes (SJ-D43).** `CustomerErased` redacts a settled order's contact/delivery fields and any parked-sale/special-order name at once; an order still in flight is left alone until it reaches a settled state (fulfilled, cancelled, voided, refunded) and is caught by an hourly sweep — the address is still needed to deliver it. The sale record itself (amounts, tax, lines) is never touched; only what identifies the customer is.

**Events**
- Consumes: `StockReceived`, `StockDeducted`, `StockAdjusted` (feeds the POS stock-position projection), `PaymentCaptured`, `PaymentFailed`, `PaymentRefunded` (order confirmation/refund status), `StoreStatusChanged`, `TenantStatusChanged`, `CustomerErased` (SJ-D43).
- Publishes: `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderFulfilled`, `OrderReturned`, `OrderVoided`, `LayawayCreated`, `LayawayCompleted`, `LayawayCancelled`.

---

### Fiscal receipts (`/admin/fiscal-receipts`)

A **gapless legal receipt sequence** — required by fiscal law in Italy, Germany (KassenSichV), Portugal, Poland, Brazil and India, and required to be demonstrable to an inspector. Distinct from `/admin/orders/{id}/receipts`, which logs how many times a document was printed or emailed; this is the document itself.

- `POST /admin/orders/{orderId}/fiscal-receipt?series=` — issue the numbered receipt. **Normally unnecessary**: the number is taken automatically when the sale completes — by the payment capture that completes a till sale, or by `confirm`. (The first version hooked only `confirm`, which the till never calls, and so numbered almost no till sales.) This is the recovery path for a sale that completed while issuance was failing, and it is idempotent — a second call returns the number already issued, because a reprint is not a sale and two numbers for one sale is how a day's takings get counted twice. Refused for a `PENDING` or `CANCELLED` order (`ORDER_NOT_SELLABLE`): numbering a basket that is never paid for is where gaps come from.
- `GET /admin/orders/{orderId}/fiscal-receipt` — the number, when it was issued, and whether the sale was later voided.
- `GET /orders/{orderId}/fiscal-receipt` — the same, authorized like the order it belongs to: any staff member, or the customer who placed it. In practice a staff read today: a signed-in customer's token carries no tenant, and the gateway derives one only for its storefront paths, which include `/orders/mine` but no read by id. The authorization filter runs at the gateway as well as in order-svc, so both had to be redeployed for this path to be reachable. This is what the till reads to print the legal number — the admin route above is management-only, so a cashier could never reach it. The number is issued when the payment that completes the sale reaches order-svc, so for a few seconds after a sale this answers `404 ORDER_RECEIPT_NOT_ISSUED`. **`?wait=N`** holds the request up to N seconds (capped at 20) for the number to be issued, so the till prints with the number after one round trip instead of polling and giving up; before payment lands a bounded wait still ends in `404`, and the cap is tested — asking for an hour gets twenty seconds. A customer who did not place the order gets the same `404`, at once.
- `GET /admin/fiscal-receipts/series?storeId=` — the counters a store runs: series code, fiscal period, the next number it will hand out, and the prefix it prints. Management-only; a cashier gets `403`, another tenant an empty list.
- `PUT /admin/fiscal-receipts/series` `{storeId, seriesCode, period, prefix}` — what is printed in front of the number, e.g. `GB-LDN-01` → `GB-LDN-01-2026-000042`. Opens the series if it is new. **The counter is never touched**: a prefix change affects the documents issued after it, and every document already issued keeps the full number it was printed with — the test issues under `OLD`, changes to `NEW`, and checks the count runs 1, 2. Prefix is letters, digits and hyphens, at most 16, upper-cased (`RECEIPT_PREFIX_INVALID`); period is a year, `2026` or `2026-04` (`RECEIPT_PERIOD_INVALID`); a refusal opens nothing. Management-only.
- `GET /admin/fiscal-receipts?storeId=&series=&period=` — the register, by number. `series` defaults to `MAIN`, `period` to the current fiscal year.
- `GET /admin/fiscal-receipts/export?storeId=&series=&period=&format=csv|json` — the register as a file (18.4): every document in number order with `prevHash` and `hash`; JSON adds the order lines behind each document. The in-repo input an accountant or a SAF-T / KassenSichV / corrispettivi export needs; **not itself a certified file** — those need a tax-authority signing key, a certified TSE, or an SDI channel this repo cannot supply. Management-only.
- `GET /admin/fiscal-receipts/audit?storeId=&series=&period=` — **the inspector's question, answered by the database rather than by assertion.** Returns the first and last numbers, how many were issued, how many the span implies, and every gap with its range. `intact: true` with an empty `gaps` array is the proof. Since 18.4 the same call gives a second, independent verdict: every document issued carries a SHA-256 over its own figures and the hash of the document before it (`prevHash`, `GENESIS` for the first), and the audit re-derives every hash in the series — `chainIntact`, `chainFrom` (documents issued before the chain existed are passed over), `chainBrokenAt` (the first document whose stored figures no longer match). A missing number and an altered figure are different findings, and now each has its own line. Contiguous holes come back as one gap with a range, because "2 to 3" is what gets explained, not two separate findings.

**Why not a Postgres `SEQUENCE`.** Sequences deliberately do not roll back: two transactions take 41 and 42, the first aborts, and 41 is gone forever. That is right for a surrogate key and wrong for a legal document, where the missing number is exactly what an inspector asks about. The counter is a row updated with `UPDATE … RETURNING` inside the sale's own transaction, so a rollback puts the number back and concurrent tills serialise on the row lock. That trade — concurrency for gaplessness — is the one every fiscal system makes, and a test drives eight tills at once to prove it holds.

**A voided sale keeps its number**, marked void with a reason. Deleting or renumbering it would close the hole, and closing the hole is the trick the numbering exists to expose: ring the sale, take the cash, void the receipt, and a till that balances hides a theft.

Numbering restarts per `period` (fiscal year) and runs per `(store, series)`, so a jurisdiction that numbers per till — Italy does — uses one series per device.

## payment-svc

### Payments & Refunds (`/payments`)
- `POST /payments` — record a payment tender against an order (POS, staff-only).
- `POST /payments/online` — capture an online storefront payment (cashless only — cash is explicitly rejected here).
- `GET /payments/{id}` — get a payment tender.
- `GET /payments/by-order/{orderId}` — list all tenders for an order.
- `POST /payments/by-order/{orderId}/refunds`, `GET /payments/by-order/{orderId}/refunds` — record/list refunds against an order's captured tender.

### Cash & Till Management
- `POST /admin/cash/till-sessions` — open a till session with an opening float.
- `GET /admin/cash/till-sessions/{id}` — get session status/float.
- `POST /admin/cash/till-sessions/{id}/drops` — record a mid-shift cash drop (safe drop).
- `GET /admin/cash/till-sessions/{id}/x-report` — X-report: read-only mid-day cash snapshot.
- `POST /admin/cash/till-sessions/{id}/close` — Z-report: end-of-day till close.
- `POST /admin/cash/movements`, `GET /admin/cash/movements` — record/list pay-in / pay-out (petty cash) movements against an open till.
- `POST /admin/cash/z-report`, `GET /admin/cash/z-report` — generate/retrieve the daily store-level Z-report reconciliation.

### Tender Mix (`/admin/reports`)
- `GET /admin/reports/tender-mix?from&to` — how the take split across payment methods: captured and refunded amounts per method, each method's share of the net, and a count of tenders that failed to capture. Refunds are subtracted **within their own method** rather than netted globally — a card sale refunded to store credit is not a zero-card day, and the split is what a merchant statement reconciles against. `shareOfNet` is null when the window's total is zero or negative, where a percentage would be misleading rather than merely odd.

**Business rules**
- Online storefront payments must be cashless (`CARD`/`UPI`/`WALLET`); cash tenders are POS-staff-only via the in-person endpoint.
- Online payment is verified against order-svc (order exists, is `ONLINE`, belongs to the caller if authenticated, amount matches the order total) before capture.
- Role tiers: `CASHIER`+ can record a POS tender (`POST /payments`). `/admin/cash/**` is on the filter's **staff-operable** tier, so `CASHIER` can reach till endpoints; `CashManagementResource` then allows `CASHIER` to open/get a till, while drops, X-report, and Z-report (close) stay `MANAGER`/`OWNER`. Cash movements (pay-in/pay-out) under `/admin/cash` also require `MANAGER`/`OWNER` at the resource layer.
- `Idempotency-Key` is honored on tender recording, refunds, and cash movements.

**Events**
- Consumes: `OrderReturned`, `OrderCancelled` (auto-refunds a previously captured, paid order).
- Publishes: `PaymentCaptured`, `PaymentFailed`, `PaymentRefunded`.

---

## pricing-svc

### Pricing (`/price-lists`, `/admin/price-lists`, `/admin/price-overrides`, `/prices`)

**Writes are management-only, reads are not.** Setting a price used to sit on `/price-lists`, outside `/admin/`, where `AdminAuthorizationFilter`'s mutation tier asks only for *some* staff role — so a CASHIER could create a price list and set what customers are charged (proved against the running stack: 201). The writes moved under `/admin/`; the reads stayed, because the POS and storefront need them and neither runs as management.

- `POST /admin/price-lists` — create a price list. `effectiveFrom` is an **ISO-8601 instant** (`2026-01-01T00:00:00Z`); the column is `TIMESTAMPTZ` and a bare date is refused with `INVALID_DATE`.
- `POST /admin/price-lists/{id}/items` — set a single variant's price on a list.
- `POST /admin/price-lists/{id}/items/batch` — set many variants' prices in one call (partial-failure tolerant).
- `POST /admin/price-lists/{id}/deactivate` · `POST /admin/price-lists/{id}/activate` — switch a price list off or on, `{"reason": "..."}` **required in both directions**. `409 PRICING_ALREADY_IN_STATE` if it is already in that state, so two people stopping the same list are not both told they did it. The resolve query filters on `active`, so a stopped list stops setting prices from the next request; orders already placed keep what they were charged. Before this, `price_lists.active` had no writer of any kind and a wrong price could only be corrected by editing the database.
- `GET /admin/price-lists/{id}/status-history` — append-only on/off trail, newest first: who switched it, when, and why.
- `GET /price-lists`, `GET /price-lists/{id}`, `GET /price-lists/{id}/items` — reads, open to any staff role.
- `POST /admin/price-overrides`, `GET /admin/price-overrides` — log/list staff-approved ad-hoc POS price overrides (an append-only audit trail, not a mutable price).
- `POST /prices/resolve` — compute the effective price + VAT breakdown for one variant/channel/quantity. Applies **line-level** promotions only: basket rules are excluded deliberately, because this answers "what does this item cost" for a product page and quoting a spend-threshold price against one item advertises a total the shopper will not be charged.
- `POST /prices/resolve-batch` — resolve prices for several lines in one call. Each line is still priced **independently**, so no basket rule can apply; use `/prices/quote` at checkout.
- `POST /prices/quote` — **price a whole basket.** Resolves every line, then runs the promotion engine over the basket as a unit, returning per-line net prices, the whole-basket discount, every promotion that applied, and the VAT computed after discounts (the basket discount is apportioned across lines by value first, so it is not VAT-free money). Takes `couponCodes`; any that do not apply come back in `rejectedCoupons` with a reason — `NO_SUCH_COUPON`, `NOT_APPLICABLE`, `COUPON_EXHAUSTED` or `COUPON_LIMIT_REACHED` — rather than being silently ignored. **Quoting never spends a coupon**: a basket is quoted on every change a shopper makes, so redemption is a separate call.
- `POST /prices/redemptions` — record that an order used these promotions, spending their usage caps. Idempotent on `(tenant, promotion, order)`, so a retried checkout or a replayed offline sale cannot burn a second use; `recorded: 0` is a successful replay, not a failure.

### Promotions (`/promotions`, `/admin/promotions`)

Same split, and for the same reason: creating a promotion is money leaving the business, and a CASHIER could do it. Writes are under `/admin/`; the storefront read is not.

- `POST /admin/promotions` — create a time-bounded promotion. Six types: `PERCENT` and `FLAT` (per line), `BASKET_PERCENT` and `BASKET_FLAT` (whole basket), `SPEND_THRESHOLD` (a flat amount once the basket clears `minOrderAmount`), and `BOGO` (`buyQty` / `getQty` / `getDiscountPct`, where 100 = free). Also takes `priority` (ascending, lower runs first), `exclusive` (stops every promotion after it), `couponCode` (unique per tenant, matched case-insensitively), and the `maxRedemptions` / `maxPerCustomer` caps.
- `GET /promotions` — list active promotions (also powers the public storefront offers banner).
- `POST /admin/promotions/{id}/items` — scope a promotion to `ALL` or to a `VARIANT`. **`CATEGORY` is refused** with `PRICING_CATEGORY_SCOPE_UNSUPPORTED`: pricing-svc has no variant→category mapping because product-svc publishes no catalogue event, and such a promotion was previously accepted, stored, and silently never applied.
- `POST /admin/promotions/{id}/deactivate` · `POST /admin/promotions/{id}/activate` — stop a running promotion or start a stopped one, `{"reason": "..."}` **required in both directions**; `409 PRICING_ALREADY_IN_STATE` if it is already in that state. Before this there was no route, no service method and no SQL statement anywhere that wrote `promotions.active` — `endsAt` is nullable, so a promotion created without one ran forever and could only be stopped by editing the database.
- `GET /admin/promotions/{id}/status-history` — append-only on/off trail, newest first. A table rather than a pair of columns on the row, because a promotion can be switched repeatedly and a record keeping only the last change cannot answer "who turned this back on?".

**Business rules**
- **Ordering is explicit.** Line-level promotions run first in `priority` order against each line's original price, then basket-level ones against the subtotal that remains. Two line-level percentages therefore compound on the original price — two 10% offers take 20%, not 19%.
- Nothing can drive a line or a basket below zero, and the clamp is **per line**, so an oversized flat discount on a cheap item cannot eat into another line's value.
- A `BOGO` counts across every line it is scoped to, not within one line — three different shirts on a buy-2-get-1 is the common case a per-line implementation gets wrong — and discounts the **cheapest** qualifying units.
- `minOrderAmount` is tested against the basket **after** line-level discounts. A basket that only clears £100 before a half-price offer has not spent £100.
- Four defects were fixed in this rebuild, all of which failed silently: `minOrderAmount` was never read, `CATEGORY` scope never matched, `store_id` was never filtered (so a store promotion ran everywhere), and the winner was chosen by `ORDER BY value DESC`, which compared a percentage against a sum of money.

### VAT & Tax Compliance
- `POST /customer-vat-status`, `GET /customer-vat-status/{customerId}` — upsert/look up a B2B customer's VAT registration & reverse-charge status.
- `POST /product-vat-categories`, `GET /product-vat-categories/{variantId}` — upsert/look up a variant's HMRC VAT tax code.
- `POST /vat-rates`, `GET /vat-rates`, `GET /vat-rates/{code}`, `PUT /vat-rates/{code}` — create/list/get/update UK VAT rates (T1/T5/T0/etc.). The code is at most 8 characters and the rate a fraction from 0 to 1; outside those, `400 VALIDATION_FAILED`.
- `POST /tax-transactions`, `GET /tax-transactions?orderId=` — record/list a POSLog-style tax transaction journal entry per order.
- `GET /vat-return?from=&to=` — the boxes of an HMRC Making Tax Digital VAT return for a date range. Management-only (`PLATFORM_ADMIN`/`OWNER`/`MANAGER`), enforced in the resource rather than by path. **Partial (SJ-D39):** boxes 1 (output VAT), 3, 5 and 6 (net sales) come from `tax_transactions`; boxes 2, 4, 7, 8 and 9 return a hardcoded `0`. Box 4 is input VAT reclaimed on purchases — purchase-svc captures it on supplier invoices and nothing carries it across, so box 5 (net VAT to pay) is overstated by exactly the VAT the business is entitled to reclaim. Not fit to file from until box 4 is real — and the return now says so itself: `computedBoxes: [1,3,5,6]`, `notComputedBoxes: [2,4,7,8,9]`, `fitToFile: false` and a `caveat` in words, so a screen or an integration cannot mistake the shape for the substance.

**Business rules**
- `POST /prices/resolve(-batch)` is deliberately open with no staff-role requirement — it's a service-to-service call order-svc makes without identity headers during checkout pricing.
- The customer-VAT-status GET endpoint has its own explicit role check (`PLATFORM_ADMIN`/`OWNER`/`MANAGER`/`STOREKEEPER`/`CASHIER`) because non-`/admin` GETs aren't covered by the shared default-deny filter (see Platform-wide conventions).
- Price overrides are append-only audit records, never edited or deleted.

**Events**
- Consumes: none.
- Publishes: `PriceChanged`, `PromotionActivated`.

---

## product-svc

### Public Storefront Catalog (`/catalog`)
- `GET /catalog/products` — browse/search products (category, free-text, SKU, or barcode; separate POS vs. online channel filtering).
- `GET /catalog/categories` — browse categories.
- `GET /catalog/products/{id}` — get a product.
- `GET /catalog/products/{id}/variants` — list a product's variants.
- `GET /catalog/products/{id}/image` — fetch the product's primary image.
- `GET /catalog/variants/by-barcode/{code}` — POS barcode-scan lookup (variant + parent product in one round-trip).

### Admin Catalog Management (`/admin`)
**Brands, Categories, Products & Variants**
- `POST/GET/GET/PUT/DELETE /admin/brands(/{id})` — brand CRUD (delete = deactivate).
- `POST/GET/GET/PUT/DELETE /admin/categories(/{id})` — category CRUD (delete = deactivate).
- `POST/GET/GET/PUT/DELETE /admin/products(/{id})` — product CRUD (delete = delist); admin list supports status/category filters.
- `POST/GET /admin/products/{id}/variants`, `GET/PUT/DELETE /admin/products/{id}/variants/{variantId}` — variant CRUD (delete = delist; editing a delisted variant is `409 VARIANT_NOT_ACTIVE`).
- `GET /admin/products/variants/resolve?ids=` — batch-resolve up to 200 variant ids to name/SKU/product context.

**Images & Store Assortment**
- `PUT/DELETE /admin/products/{id}/image` — upload/replace (raw bytes, strictly <256KB) or remove a product's primary image. The admin app downscales and re-encodes to that budget before uploading; the service rejects anything at or above it, and a CHECK constraint on `product_images` enforces the same bound at the storage layer.
- `GET/PUT /admin/products/{id}/stores` — get/replace a product's per-store assortment (empty list = sold everywhere).

**Units of Measure**
- `GET /admin/uom/classes`, `GET /admin/uom/units` — list UOM classes and unit definitions.
- `GET /admin/uom/convert` — convert a quantity between two UOMs (global or variant-specific factor).
- `POST/GET/DELETE /admin/uom/item-conversions(/{id})` — variant-specific UOM conversion factors.

**Item Templates**
- `POST/GET/GET/DELETE /admin/item-templates(/{id})` — attribute-template CRUD.
- `POST /admin/item-templates/{id}/apply/{variantId}` — apply a template's attributes to a variant.

**Cross-References, Relationships & Revisions**
- `POST/GET/DELETE /admin/products/variants/{variantId}/cross-references(/{id})` — supplier/customer part-number cross-references.
- `POST/GET/DELETE /admin/products/variants/{variantId}/relationships(/{id})` — related-item links between variants (e.g. substitute, accessory).
- `POST/GET /admin/products/variants/{variantId}/revisions`, `GET .../current`, `GET .../{id}` — create/list/get dated revisions of a variant's spec.

**Bulk Import**
- `POST /admin/import` — bulk-import categories + products/variants from a JSON payload (partial success, per-row errors, duplicate SKUs reported not fatal).
- `POST /admin/import/supplier-csv` — import a supplier catalogue CSV.

**Catalog Groups & Container Types**
- `POST/GET/GET/DELETE /admin/catalog-groups(/{id})`, `POST/DELETE .../elements(/{elementId})` — merchandising catalog-group CRUD and membership.
- `POST/GET/PUT/DELETE /admin/products/variants/{variantId}/catalog-assignment` — assign a variant's catalog group.
- `POST/GET/PUT/DELETE /admin/container-types(/{id})` — packaging/container type CRUD.
- `POST/GET/DELETE /admin/products/variants/{variantId}/container-links(/{id})` — link a variant to a container type.

**Attribute Groups & Category Sets**
- `GET /admin/attribute-groups(/{groupCode})` — structured attribute-group definitions and fields.
- `PUT/GET/GET/DELETE /admin/products/variants/{variantId}/attribute-groups(/{groupCode})` — set/get/remove a variant's attribute values.
- `POST/GET/GET/PUT/DELETE /admin/category-sets(/{id})`, `POST/GET/DELETE .../members(/{categoryId})` — alternate category-hierarchy ("category set") CRUD and membership.
- `POST/GET/DELETE /admin/products/variants/{variantId}/category-set-assignments(/{setId})` — assign a variant into a category set.

**Business rules**
- Public catalog shows only `ACTIVE` products; the online list further filters to `sellable_online`, POS channel to `sellable_pos`.
- Bulk import never hard-fails a batch — it always returns 200 with a success count plus any per-row errors.

**Events**
- Consumes: none.
- Publishes: `ProductCreated`, `ProductUpdated`, `ProductDelisted`, `VariantCreated`, `ItemTemplateCreated`, `ItemTemplateApplied`, `ItemRevisionCreated`.

---

### Food safety, origin and age-restricted sales

Four capabilities that are law rather than product strategy. The reads sit on `/catalog` deliberately — a shopper is entitled to allergen information before buying, and a till needs the age check — while every write is management-only under `/admin/`.

- `GET /catalog/allergens` — the fourteen allergens Regulation (EU) 1169/2011 Annex II names. Reference data, seeded, read-only: a tenant that could edit the list could quietly delete one.
- `PUT /admin/products/variants/{variantId}/allergens` — declare a variant's allergens. **Replaces** the whole declaration, so a mistake can be corrected; merging would make "we were wrong, it has no celery" unsayable. Each entry is `CONTAINS` or `MAY_CONTAIN`, and the distinction is legal rather than cosmetic — `MAY_CONTAIN` is a cross-contamination warning. Declaring the same allergen twice is refused (`PRODUCT_DUPLICATE_ALLERGEN`) rather than silently resolved to the stricter one.
- `GET /catalog/variants/{variantId}/allergens` — the declaration, open to shoppers. **Read the `status`, not the list length.** `UNDECLARED` with an empty list means nobody has checked; `DECLARED` with an empty list means the product has been checked and contains none of the fourteen. Sending an empty list to the PUT is how the second is said — a positive statement, not an omission. Treating the first as the second is how an allergic customer is told a product is safe when nobody knows.
- `GET /admin/products/by-allergen/{code}?presence=` — every product carrying one allergen; the query a recall runs. Both presences by default, because a withdrawal usually has to cover the may-contains too.
- `GET /admin/products/allergen-gaps` — food products nobody has declared yet, oldest first. The list an inspector asks for. An item counts as food once it is marked so: `"food": true` on the compliance PUT moves it from `NOT_APPLICABLE` to `UNDECLARED`, and it leaves this list when its allergens are declared. `"food": false` makes it `NOT_APPLICABLE` and removes any declaration; omitting the flag leaves the status alone, and re-marking a declared item as food never turns its declaration back into an unknown. (SJ-D42: until the flag existed nothing wrote `UNDECLARED` — the column defaults to `NOT_APPLICABLE`, whatever V17's comment says — so this list could never return a row.)
- `PUT /admin/products/variants/{variantId}/compliance` — country of origin (ISO 3166-1 alpha-2, mandatory for unprocessed meat, fish, fruit, veg, honey, olive oil and wine under EU 1169/2011 art.26), ingredients, the age-restriction category, and the weighed-item fields: `soldBy` (EACH/WEIGHT/VOLUME/LENGTH), `netContent` + `netContentUom` for the unit price a shelf edge must display (Price Marking Order 2004), `tareWeight` a scale deducts, and `catchWeight` for items whose price is not knowable until they are weighed. An item not sold by the each with no unit named is refused — a shelf edge could not price it.
- `GET /catalog/variants/{variantId}/compliance` — the same, for a shelf edge and a scale.
- `GET /catalog/variants/{variantId}/age-check?country=GB` — what the till must ask before taking payment. **Read `restricted`, never the absence of `minimumAge`**: a null field is omitted from the JSON entirely, so a client inferring "no age given, therefore sell it" cannot tell an unrestricted item from a field that went missing. `restricted` is always present.
- `GET /admin/age-restriction-rules?country=` · `PUT /admin/age-restriction-rules` — the statutory defaults with this tenant's overrides shadowing them. A tenant rule may be **stricter than the statute and never laxer** (`PRODUCT_AGE_BELOW_STATUTORY`): a chain adopting Challenge-25 is making a policy decision, a chain setting alcohol to 16 in the UK is committing an offence, and a system that lets them configure it has helped.

**Why the age is not on the product.** The same bottle of wine is 18 in the UK, 18 in China, 20 in Japan and 21 in the US. The variant carries the *category* (`ALCOHOL`, `TOBACCO`, `KNIVES`, `SOLVENTS`, `FIREWORKS`, `LOTTERY`, `VIDEO_18`, `NICOTINE_VAPE`, `CORROSIVES`, `PETROL`) and the age is resolved per country at the till. India's drinking age varies by state, so the seeded default is the conservative 21 and a tenant trading where it differs overrides it. The endpoint takes the country rather than a store id on purpose: this is asked for every restricted line scanned, and a second network hop belongs nowhere near a queue.

A restricted item in a country with **no** rule for it returns `400 PRODUCT_NO_AGE_RULE` rather than "no restriction" — a missing rule is a gap in configuration, not a licence to sell.

## purchase-svc

### Suppliers & Purchase Orders
- `POST /suppliers`, `GET /suppliers`, `GET /suppliers/{id}`, `PUT /suppliers/{id}` — onboard/list/get/correct suppliers. `PUT` (management-only) replaces name, VAT details, country, currency and terms — SJ-D34 found none of them could be corrected after creation, which mattered once purchase orders inherited the supplier's currency. The currency can change only while no purchase order against the supplier is open (`409 PURCHASE_SUPPLIER_CURRENCY_IN_USE`, with the count); orders already raised keep the currency they were raised in. A name already taken is `409 PURCHASE_SUPPLIER_DUPLICATE`. `currency` is the currency **this supplier invoices in**, defaulting to the tenant's own declared currency; set it explicitly for an overseas supplier (a Japanese supplier billing a UK tenant in JPY).
- `POST /purchase-orders` — create a purchase order for a supplier. **The order's currency comes from the supplier**, not the tenant and not a default — a purchase order is a commitment to pay whoever invoices (SJ-D24). Naming a currency that contradicts the supplier's is refused with `PURCHASE_CURRENCY_MISMATCH` rather than silently overridden; a non-ISO-4217 code is `PURCHASE_INVALID_CURRENCY`.
- `GET /purchase-orders`, `GET /purchase-orders/{id}` — list/get purchase orders.
- `POST /purchase-orders/{id}/submit` — submit a draft PO. Lands in `SUBMITTED` when the order's **net** value is within the submitter's own spend authority, and in `PENDING_APPROVAL` when it is not; either way the submission is recorded in the order's append-only approval trail. Spend authority is configured per **currency and role** (`shelfj.purchase.approval.limits`, e.g. `GBP:MANAGER:5000,JPY:MANAGER:800000,GBP:OWNER:UNLIMITED`) — there is no FX handling in Shelf-J, so a ceiling in one currency cannot be compared against an order in another. **Unconfigured means off**: with no limits at all, any staff role may submit any amount, exactly as before the feature existed. Once anything is configured, a currency with no entry **fails closed** rather than granting unlimited authority.
- `POST /purchase-orders/{id}/approve` — release a `PENDING_APPROVAL` order to the supplier. The approver's own authority is checked against the same figure by the same rule, so **separation of duties falls out of the limits**: an order is only pending because it exceeded the submitter's ceiling, so that same person is refused with `PURCHASE_APPROVAL_EXCEEDS_AUTHORITY`. There is deliberately no separate self-approval rule — it would be redundant, and would deadlock a single-owner shop.
- `POST /purchase-orders/{id}/reject` — send it back to `DRAFT` with a **required** reason. Needs no spend authority: refusing to commit money is not itself a commitment, and requiring it would strand an order too large for anyone configured.
- `GET /purchase-orders/{id}/approvals` — the append-only approval trail, newest first. Each row carries the net value and the authority **as they stood at the time**, so it still answers "was that person allowed to commit that much?" after the configuration changes — and because a rejected order can be edited before resubmission, the figure a decision was made against is not necessarily the one the order carries now.
- `GET /purchase-orders/spend-authority?currency=GBP` — what the caller may commit, so a buyer learns their ceiling before building the order rather than after trying to submit it.
- `POST /supplier-invoices` — **capture a supplier's invoice and match it three ways.** Quantity is matched against what was **received**, not ordered — you pay for what turned up, and an order for 100 that delivered 60 and invoiced 60 is correct. Price is matched against the **order**, because a goods receipt records quantity only. Invoiced quantity is compared **cumulatively across every invoice on the order**, so a supplier who delivers and bills in two parts is not flagged on the second (the same shape partial receipt established for the received leg). The invoice is stored either way: `MATCHED` or `FLAGGED`. **Flagging never blocks capture** — an invoice that arrived is a fact, and refusing to record one that disagrees destroys the evidence of the disagreement. A duplicate invoice number for the same supplier is `409 PURCHASE_INVOICE_DUPLICATE`, matched case-insensitively because the reference is printed on paper and typed by a human. An invoice in a currency the order was not placed in is `400 PURCHASE_CURRENCY_MISMATCH` — that is a different document, not a variance.
- `GET /supplier-invoices`, `GET /supplier-invoices/{id}` — list (optionally `?poId=`) and get, each line carrying ordered / received / previously-invoiced / invoiced and both unit prices. Variances are the ones **stored at capture**, not recomputed: a purchase order can be amended after an invoice is flagged, and re-matching on read would silently erase the disagreement it was flagged for. Codes: `INVOICED_ABOVE_RECEIVED`, `NOT_RECEIVED`, `NOT_ON_ORDER`, `PRICE_ABOVE_ORDER`, `PRICE_BELOW_ORDER`. Tolerance bands are per deployment (`shelfj.purchase.match.tolerance.price-percent` / `.qty-percent`) and default to zero, which surfaces every difference — choosing a band is a procurement policy.
- `POST /purchase-orders/{id}/lines`, `GET /purchase-orders/{id}/lines` — add/list PO line items. **Adding a line restates the order's totals** in the same transaction (SJ-D22): `totalNet` from the lines, `totalVat` from each line's VAT code rated against pricing-svc's table for the tenant, `totalGross` from the two. Before this the three columns were inserted as zero and never written again, so every purchase order in the product reported a value of `0.00`. A VAT code with no configured rate contributes zero rather than refusing the line — VAT rates are tenant configuration nothing seeds, so a fresh tenant genuinely has none.
- `POST /purchase-orders/{id}/cancel` — cancel a DRAFT or SUBMITTED order with a required reason. A RECEIVED one is refused: stock is booked against it (SJ-D3).
- `GET /purchase-orders/{id}/progress` — **ordered against received, line by line, with the balance still due.** This is what a `PARTIALLY_RECEIVED` status does not tell you: a buyer chasing a supplier needs to know *what* is missing. Receipts are matched to order lines by variant rather than by line id, because a delivery note names products, not order rows.
- `POST /purchase-orders/{id}/close` — short-close a `PARTIALLY_RECEIVED` order with a required reason: the balance is never arriving and we have stopped waiting. Refused on any other status — nothing delivered is a cancellation, everything delivered is already RECEIVED.
- `POST /goods-receipts` — record goods received against a purchase order. Accepts partial deliveries.
- `GET /goods-receipts?poId=` — list goods receipts for a PO.

### Intercompany & Ledger
- `POST /intercompany-invoices` — raise a matched AR/AP invoice pair for an inter-org inventory transfer.
- `GET /intercompany-invoices`, `GET /intercompany-invoices/{id}` — list/get intercompany invoices.
- `POST /intercompany-invoices/{id}/settle` — settle an intercompany invoice.
- `GET /nominal-ledger` — read-only double-entry nominal ledger (filter by nominal code/date range).

**Business rules**
- Purchase orders run create (draft) → add lines → submit before goods can be received against them.
- **The status vocabulary is `DRAFT → SUBMITTED → PARTIALLY_RECEIVED → RECEIVED`**, with `CLOSED` (short-closed, balance abandoned) and `CANCELLED` (nothing ever received) as the two terminal exits. `CLOSED` is deliberately not `RECEIVED`: "we got it all" and "we gave up on the rest" are different facts, and a supplier scorecard that cannot tell them apart is worthless.
- **A partial delivery no longer closes the order.** A receipt is compared against what was ordered — under a `FOR UPDATE` lock on the order, so two deliveries arriving at once cannot both book the same balance — and the order lands in `PARTIALLY_RECEIVED` or `RECEIVED` accordingly. Previously the status was set to `RECEIVED` with no reference to quantity, so 6 of 10 closed the order *and the second delivery of the remaining 4 was then refused*, stranding the balance with no PO to receive it against.
- **Over-receipt is refused** (`422 PURCHASE_OVER_RECEIPT`), cumulatively across deliveries rather than per delivery. Accepting more than was ordered would book stock nobody asked for against an order that cannot account for it, and a mistyped 60 for 6 would do it silently. Whether a tolerance band should be allowed is a per-tenant procurement policy, not something to invent.
- Goods receipt posting accepts an `Idempotency-Key`; a replayed receipt does not count its quantity twice.
- The nominal ledger is read-only — a FRS 102/UK GAAP-style journal view, not an editable resource.

**Events**
- Consumes: none.
- Publishes: `PurchaseOrderCreated`, `GoodsReceived`, `IntercompanyInvoiceRaised`.

---

## reporting-svc

### Inventory Analytics (`/admin/reports/inventory`)
- `GET /admin/reports/inventory/on-hand` — cross-store on-hand stock snapshot.
- `GET /admin/reports/inventory/supply-demand` — supply/demand netting (on-hand + open in-transit supply lines).
- `GET /admin/reports/inventory/movement-stats` — stock movement statistics bucketed by day/week/month.

### Sales Analytics (`/admin/reports/sales`)
- `GET /admin/reports/sales/summary` — gross/refunded/net revenue and order count for a date range, grouped by currency.
- `GET /admin/reports/sales/by-day` — daily revenue buckets per currency, newest day first.

**Business rules**
- Entirely read-only: every endpoint serves from projections built by consuming events, never a live call into order/payment/inventory-svc.
- Sales figures are computed net of refunds.

**Events**
- Consumes: `OrderConfirmed`, `PaymentRefunded` (sales projection), `StockReceived`, `StockDeducted`, `StockAdjusted`, `TransferOrderShipped`, `TransferOrderReceived` (stock movement projection).
- Publishes: none.

---

## tenant-svc

### Onboarding (`/onboarding`)
- `POST /onboarding` — single-call signup: create a tenant and its first store atomically.
- `POST /onboarding/tenants` — create just the tenant (binds it to the caller as owner).
- `POST /onboarding/stores` — create the tenant's (first/default) store.
- `GET /onboarding/status` — onboarding checklist/completion status.

### Tenant, Store, Zone & Staff Administration (`/admin`)
- `GET/PUT /admin/tenant` — get/update the tenant's own profile.
- `POST/GET /admin/stores`, `GET/PUT /admin/stores/{storeId}`, `PATCH /admin/stores/{storeId}/status` — create/list/get/update/activate-suspend a store.
- `POST/GET /admin/stores/{storeId}/zones`, `GET/PUT /admin/stores/{storeId}/zones/{zoneId}`, `PATCH .../status` — create/list/get/update/activate-suspend a zone.
- `POST /admin/staff` — assign a staff member to a store with a role.
- `GET /admin/staff` — list staff assignments.
- `DELETE /admin/staff/{userId}?store=` — remove a staff member's role at a specific store (role assignment is per-store, not tenant-wide).

### Inventory Configuration (`/admin/inventory-config`)
- `PUT /admin/inventory-config`, `GET /admin/inventory-config` — upsert/get tenant-wide inventory control parameters (lot, serial, grade, costing, UOM policy).

### Platform Administration (`/platform`, `PLATFORM_ADMIN` only)
- `GET /platform/tenants` — list every tenant on the platform.
- `PATCH /platform/tenants/{tenantId}/status` — activate/suspend a tenant platform-wide.

### Public Storefront Config (`/storefront`)
- `GET /storefront/config?store=` — per-store storefront display config (e.g. whether to show prices).
- `GET /storefront/active` — whether the tenant may currently transact (the gateway's storefront suspension gate).
- `GET /storefront/stores` — active stores for the tenant (storefront's store switcher).

**Business rules**
- Onboarding is designed as a single atomic call (tenant + first store) so a new signup needs no JWT refresh mid-flow.
- Platform-wide tenant visibility/suspension is restricted to `PLATFORM_ADMIN`.
- tenant-svc is the system of record for tenant/store/zone/staff state — it's a pure event publisher, never a consumer.

**Events**
- Consumes: none.
### Weighing instruments (`/admin/stores/{storeId}/weighing-instruments`)

The register of every instrument a store weighs for trade on (Weights and Measures Act 1985 s.11: using an instrument not passed as fit for use for trade, or one whose stamp a repair has broken, is an offence), and an append-only history of every verification, inspection and repair. Standing is derived on every read, never stored: **certified** means in service, latest history entry a pass, and not yet due again; otherwise `NEVER_VERIFIED`, `FAILED`, `REPAIRED_SINCE`, `OVERDUE`, `OUT_OF_SERVICE` or `RETIRED`. Reads need any staff role at the store (the till asks `?certified=true` before it sells by weight); writes are management-only.

- `GET /admin/stores/{storeId}/weighing-instruments?certified=` · `POST …` · `GET/PUT …/{id}` · `PATCH …/{id}/status` (`IN_SERVICE`, `OUT_OF_SERVICE`, `RETIRED`; retirement is final — a replaced or repaired-beyond-recognition scale is a new instrument). An instrument has an identifier unique in the store and a serial number unique in the tenant, a kind (`COUNTER`, `LABELLING`, `PLATFORM`, `HANGING`), capacity and scale interval as marked, the type-approval reference, and for a labelling scale the `labelScheme` JSON the till reads its barcodes with (`{prefixes, itemDigits, valueKind: PRICE|WEIGHT, valueDecimals, priceCheckDigit?}`), validated here once rather than trusted on every scan.
- `GET/POST …/{id}/verifications` — the history: `INITIAL`, `RE_VERIFICATION`, `INSPECTION` or `REPAIR`, with who did it, the certificate reference, whether it passed and when it is due again. A `REPAIR` is never a pass; a due date before the work, a date in the future, or an entry on a retired instrument is refused.

- Publishes: `TenantCreated`, `StoreCreated`, `ZoneCreated`, `StaffAssigned`, `TenantStatusChanged`, `StoreStatusChanged`, `UserRoleGranted`.

---

*Companion documents: [README.md](../README.md) (product tour) · [docs/UI-GUIDE.md](UI-GUIDE.md) (UI surface by persona/screen) · [docs/ARCHITECTURE.md](ARCHITECTURE.md) (engineering reference, per-service tables/events summary in §10) · [PRD.md](../PRD.md).*
