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

The gateway is the single public door to the whole platform — nothing else is internet-reachable. Every call comes in as `/api/{service}/{path...}` (an equivalent `/api/v1/{service}/{path...}` form exists too; the version prefix is currently informational, and the unversioned alias is marked `Deprecation`/`Link`-to-`/api/v1` on every response to steer clients forward). The gateway only proxies to the 12 declared business services (an explicit allowlist — `iam-svc, tenant-svc, product-svc, inventory-svc, pricing-svc, cart-svc, order-svc, payment-svc, purchase-svc, customer-svc, notification-svc, reporting-svc`); anything else, even if it happens to be registered in service discovery, is unreachable. It terminates and verifies the JWT, strips any client-supplied identity headers, and re-stamps `X-Tenant-Id` / `X-User-Id` / `X-Roles` from the verified token so every business service can trust those headers unconditionally; it also forwards `Idempotency-Key` and the real `Content-Type` (so binary uploads like product images pass through intact) untouched.

Guest/customer storefront access is carved out by an explicit path whitelist keyed on an `X-Storefront-Tenant` header (catalog browse, price resolve, active promotions, per-store storefront config, stock availability, and — for a signed-in customer only — placing an order and paying online); everything else requires a verified staff or customer Bearer token.

Protective features: Redis-backed rate limiting (default 100 req/min per caller, so it holds across gateway replicas), brute-force login lockout (5 failed attempts → 15-minute block on `/auth/login`), a deny-by-default CORS policy (only explicitly configured browser origins get CORS headers), a per-upstream circuit breaker (opens after 5 consecutive connect/read failures for 10s and fails fast with a clean 503 instead of piling up timeouts), connect/read timeouts (2s/10s, surfaced as 502/504 rather than hangs), and a tenant-status gate that polls tenant-svc's `storefront/active` flag (cached 15s, fails open) so a suspended tenant's storefront stops serving without the gateway holding its own tenant data.

---

## Platform-wide conventions

These apply across (almost) every endpoint below and are called out per-service only where a service deviates from them:

- **Tenant scoping**: every request is scoped to the caller's tenant, taken from the gateway-verified identity (or, on public storefront paths, from the resolved storefront tenant) — never from a client-supplied body field.
- **Default-deny RBAC**: role model is `PLATFORM_ADMIN` (cross-tenant), `OWNER`, `MANAGER`, `STOREKEEPER`, `CASHIER`, `CUSTOMER`. The shared `AdminAuthorizationFilter` enforces four tiers: **(1)** most of the `/admin/...` subtree — every HTTP method, *including GETs* — requires a management role (`PLATFORM_ADMIN`/`OWNER`/`MANAGER`), with bootstrap/receipt exemptions (`POST /admin/tenant` during onboarding, `POST .../receipts`); **(2)** **staff-operable admin surfaces** require any staff role (`STOREKEEPER`/`CASHIER` included): `/admin/inventory/**` (warehouse), `/admin/cash/**` (till — resource layer may still demand MANAGER+ for close/drops), plus support GETs `GET /admin/tenant`, `GET /admin/stores…`, `GET /admin/products/variants/resolve`; **(3)** `POST .../refunds` and `POST .../void` require a management role even outside `/admin/`; **(4)** any other mutating request needs at least a staff role, except a small open allowlist (identity endpoints, onboarding, `/prices/resolve*`, `/orders`, `/cart*`, `/payments/online`, `/inventory/reservations*`). Non-`/admin` GETs outside the staff-admin list are not gated by this filter.
- **Pagination**: list endpoints are cursor-paginated — `?after=<meta.nextCursor>&limit=1-100` (default ~20).
- **Idempotency**: money- or stock-moving POSTs (place order, reserve/receive/adjust stock, record payment/refund/cash movement, goods receipt) accept an `Idempotency-Key` header so a retried POST is safe; placing an order requires one.
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
- `DELETE /customers/{id}` — GDPR erasure/anonymize a customer.
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
- Publishes: `CustomerRegistered`, `LoyaltyEarned`, `LoyaltyRedeemed`, `LoyaltyAdjusted`, `StoreCreditIssued`, `StoreCreditRedeemed`.

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

### Bootstrap (`/bootstrap`)
- `POST /bootstrap/admin` — one-time creation of the first `PLATFORM_ADMIN` for a fresh deployment (refuses once one already exists; not JWT-gated by necessity).

### Identity (`/auth/me`)
- `GET /auth/me` — resolve the caller's own identity, roles, and profile from their bearer token.

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
- Publishes: `UserRegistered`.

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
- Consumes: `GoodsReceived` (from purchase-svc), `OrderFulfilled`, `OrderReturned`, `OrderCancelled` (from order-svc — deduct / restock / release hold).
- Publishes: `StockReceived`, `StockReserved`, `StockReleased`, `StockDeducted`, `StockAdjusted`, `StockBelowThreshold`, `ReplenishmentSuggested`, `ReplenishmentResolved`, `SerialsRegistered`, `SerialStatusChanged`, `MaterialStatusChanged`, `TransferOrderShipped`, `TransferOrderReceived`, `TransferOrderCancelled`, `MoveOrderCompleted`, `MoveOrderCancelled`, `CycleCountAdjusted`, `PhysicalInventoryCreated`, `PhysicalInventoryCompleted`, `CostingMethodUpdated`, `AccountingPeriodOpened`, `AccountingPeriodClosed`, `KanbanTriggered`, `KanbanReplenished`, `KanbanCreated`, `RopPlanUpdated`, `RopComputed`, `LotSplit`, `LotMerge`.

---

## notification-svc

### Notifications (`/admin/notifications` + send)
Fan-in from Kafka events, plus a staff send path for POS receipts etc.
- `GET /admin/notifications/shortage-alerts` — list low-stock shortage alerts (by store or variant).
- `GET /admin/notifications` — in-app notification feed (welcome, order-confirmation, POS receipt, etc.), newest first.
- `POST /notifications/send` — staff-triggered send (order-svc uses this for EMAIL receipts). Body: `recipient`, `subject`, `body`, optional `type`/`eventId`.

**Business rules**
- Channel is selected by `shelfj.notification.channel`: `app` (default, in-app only), `email`/`smtp` (SMTP **plus** in-app via a composite channel so the feed still fills when email is on), or `mqtt` (device-facing push — POS terminals, kiosk/back-store displays, platform console — **plus** in-app; topic `shelfj/notifications/{tenantId}/{recipient}`).
- MQTT auth: every client (including this service's own publisher connection) presents a shelfj platform JWT as the MQTT password; the broker (EMQX) verifies it and ties the connecting username to the JWT's `tenant` claim, then ACL-scopes reads to that tenant's own topic subtree — see `infra/emqx.conf` / `infra/emqx-acl.conf`.

**Events**
- Consumes: `OrderConfirmed` (order-confirmation notice), `StockBelowThreshold` (shortage alert), `UserRegistered` (welcome/registration notice).
- Publishes: none.

---

## order-svc

### Order Lifecycle (`/orders`)
- `GET /orders` — list orders for the tenant (filter by store/channel/status/date range).
- `GET /orders/mine` — the signed-in customer's own order history (never another customer's, never the tenant's full book).
- `POST /orders` — place a new order (POS or ONLINE channel); requires an `Idempotency-Key`.
- `GET /orders/{id}` — get an order with its line items.
- `POST /orders/{id}/confirm` — confirm an order (e.g. once payment settles).
- `POST /orders/{id}/cancel` — cancel an order pre-fulfilment.
- `POST /orders/{id}/fulfil` — mark an order fulfilled (stock deducted, ready for handover/pickup/delivery).
- `GET /orders/{id}/history` — order status history/audit trail.
- `POST /orders/{id}/void` — void a completed POS sale (post-fulfilment correction, distinct from cancel).
- `POST /orders/{id}/returns` / `GET /orders/{id}/returns` — create/list returns against an order (full or partial).

### POS Operations
- `POST /pos/parked-sales`, `GET /pos/parked-sales`, `GET /pos/parked-sales/{id}`, `DELETE /pos/parked-sales/{id}` — park (suspend), list, get, or cancel an in-progress POS sale so a cashier can serve another customer and resume later.
- `POST /pos/no-sale` — log a no-sale/open-drawer event.
- `POST /admin/pos-log/orders/{orderId}` — record the POSLog transaction-journal entry for a fulfilled POS order.
- `GET /admin/pos-log`, `GET /admin/pos-log/orders/{orderId}` — list the POS transaction journal, tenant-wide or per order.
- `GET /admin/pos/stock-positions` — live (eventually-consistent) on-hand snapshot for POS screens, fed asynchronously from inventory-svc events.
- `POST /admin/orders/{orderId}/receipts`, `GET /admin/orders/{orderId}/receipts` — log/list receipt generation events (print or email; the frontend renders the receipt itself).

### Alternative Sale Types
- `POST /gift-cards` — issue a gift card. `GET /gift-cards/{code}` — look one up. `POST /gift-cards/{code}/reload` — top up balance. `POST /gift-cards/{code}/redeem` — spend from balance. `GET /gift-cards/{code}/transactions` — transaction history.
- `POST /layaways` — create a layaway (reserve goods against installment deposits). `GET /layaways/{id}` — get with items/deposits. `POST /layaways/{id}/deposits` — record a deposit. `POST /layaways/{id}/complete` — final payment received, hand over goods. `POST /layaways/{id}/cancel` — cancel.
- `POST /admin/special-orders` — create a special order (customer order for future delivery, no immediate stock deduction). `GET /admin/special-orders`, `GET .../{id}` — list/get. `POST .../{id}/confirm`, `.../fulfil`, `.../cancel` — lifecycle actions.

**Business rules**
- Placing an order requires an `Idempotency-Key` (rejected with 400 otherwise) — the one hard requirement across the whole surface.
- `POS` channel orders require a `CASHIER`/`MANAGER`/`OWNER` role; `ONLINE` orders need no staff role — but the gateway only forwards order placement for a verified, signed-in customer token (there is no anonymous guest checkout).
- Special orders deliberately skip immediate inventory deduction, unlike regular POS/online orders.
- Void, cancel, and return are three distinct lifecycle actions: void corrects a completed sale, cancel stops an unfulfilled order, return is a post-sale reversal.
- The POS stock-position projection is explicitly documented as eventually consistent and must not be used to make reservation decisions.

**Events**
- Consumes: `StockReceived`, `StockDeducted`, `StockAdjusted` (feeds the POS stock-position projection), `PaymentCaptured`, `PaymentFailed`, `PaymentRefunded` (order confirmation/refund status), `StoreStatusChanged`, `TenantStatusChanged`.
- Publishes: `OrderPlaced`, `OrderConfirmed`, `OrderCancelled`, `OrderFulfilled`, `OrderReturned`, `OrderVoided`, `LayawayCreated`, `LayawayCompleted`, `LayawayCancelled`.

---

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

### Pricing (`/price-lists`, `/admin/price-overrides`, `/prices`)
- `POST /price-lists`, `GET /price-lists`, `GET /price-lists/{id}` — create/list/get price lists.
- `POST /price-lists/{id}/items` — set a single variant's price on a list.
- `POST /price-lists/{id}/items/batch` — set many variants' prices in one call (partial-failure tolerant).
- `GET /price-lists/{id}/items` — list a price list's items.
- `POST /admin/price-overrides`, `GET /admin/price-overrides` — log/list staff-approved ad-hoc POS price overrides (an append-only audit trail, not a mutable price).
- `POST /prices/resolve` — compute the effective price + VAT breakdown for a variant/channel/quantity.
- `POST /prices/resolve-batch` — resolve prices for every line of an order in a single call.

### Promotions (`/promotions`)
- `POST /promotions` — create a time-bounded promotion (percent or flat discount, scoped to all products, a variant, or a category).
- `GET /promotions` — list active promotions (also powers the public storefront offers banner).
- `POST /promotions/{id}/items` — add a targeted item to a promotion.

### VAT & Tax Compliance
- `POST /customer-vat-status`, `GET /customer-vat-status/{customerId}` — upsert/look up a B2B customer's VAT registration & reverse-charge status.
- `POST /product-vat-categories`, `GET /product-vat-categories/{variantId}` — upsert/look up a variant's HMRC VAT tax code.
- `POST /vat-rates`, `GET /vat-rates`, `GET /vat-rates/{code}`, `PUT /vat-rates/{code}` — create/list/get/update UK VAT rates (T1/T5/T0/etc.).
- `POST /tax-transactions`, `GET /tax-transactions?orderId=` — record/list a POSLog-style tax transaction journal entry per order.
- `GET /vat-return?from=&to=` — compute an HMRC Making Tax Digital VAT return (boxes 1–9) for a date range.

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
- `POST/GET /admin/products/{id}/variants`, `GET/PUT/DELETE /admin/products/{id}/variants/{variantId}` — variant CRUD (delete = delist).
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

## purchase-svc

### Suppliers & Purchase Orders
- `POST /suppliers`, `GET /suppliers`, `GET /suppliers/{id}` — onboard/list/get suppliers.
- `POST /purchase-orders` — create a purchase order for a supplier.
- `GET /purchase-orders`, `GET /purchase-orders/{id}` — list/get purchase orders.
- `POST /purchase-orders/{id}/submit` — submit a draft PO.
- `POST /purchase-orders/{id}/lines`, `GET /purchase-orders/{id}/lines` — add/list PO line items.
- `POST /goods-receipts` — record goods received against a purchase order.
- `GET /goods-receipts?poId=` — list goods receipts for a PO.

### Intercompany & Ledger
- `POST /intercompany-invoices` — raise a matched AR/AP invoice pair for an inter-org inventory transfer.
- `GET /intercompany-invoices`, `GET /intercompany-invoices/{id}` — list/get intercompany invoices.
- `POST /intercompany-invoices/{id}/settle` — settle an intercompany invoice.
- `GET /nominal-ledger` — read-only double-entry nominal ledger (filter by nominal code/date range).

**Business rules**
- Purchase orders run create (draft) → add lines → submit before goods can be received against them.
- Goods receipt posting accepts an `Idempotency-Key`.
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
- Publishes: `TenantCreated`, `StoreCreated`, `ZoneCreated`, `StaffAssigned`, `TenantStatusChanged`, `StoreStatusChanged`, `UserRoleGranted`.

---

*Companion documents: [README.md](../README.md) (product tour) · [docs/UI-GUIDE.md](UI-GUIDE.md) (UI surface by persona/screen) · [docs/ARCHITECTURE.md](ARCHITECTURE.md) (engineering reference, per-service tables/events summary in §10) · [PRD.md](../PRD.md).*
