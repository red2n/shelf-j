# Shelf-J — API ↔ UI Gap Analysis

> **Date:** 2026-06-14 · **Last re-audited:** 2026-06-15 · **Last updated:** 2026-06-16 (see §13 session changelog)
> **Method:** Fresh code-level audit. Every `@GET/@POST/@PUT/@DELETE/@PATCH` resource method across `services/*/api/` was enumerated and cross-referenced against every HTTP call the Flutter app (`frontends/shelf-app/lib`) actually makes. This document is derived only from source code, not from any prior gap notes.
> **Scope:** backend REST surface vs. the single front-end (`shelf-app`, which hosts Admin + Platform + Storefront + POS in one Flutter app).

---

## 1. Headline numbers

> **Re-audited 2026-06-15** (full re-enumeration of every `@GET/@POST/@PUT/@DELETE/@PATCH` across the 12 services vs every `dio` call shape in `frontends/shelf-app/lib`). The original figures (kept for contrast) were taken at project start.

| Metric | At start | **Now (2026-06-15)** |
|---|---|---|
| REST endpoints across the 12 services (excl. `sample-svc` template) | ~344 | **345** |
| Distinct endpoint shapes the UI calls | ~30 | **~95** |
| Backend surface with **no** UI consumer | ~90 % | **~72 %** |
| Of those orphans, in the two deferred "deep" modules (inventory WMS + product PIM) | — | **~182** |
| Orphans **outside** those modules (the actionable list) | — | **~65** |

The headline has shifted: the front-end now exercises the **entire commerce spine** (catalog → pricing → cart/checkout → POS multi-tender → payments → returns → cash → receipts) plus store/zone/staff/procurement/pricing/customer admin. What remains orphaned splits cleanly into **(a)** two large, deliberately-deferred advanced modules and **(b)** a focused set of "next" endpoints (§12).

---

## 2. What the UI actually consumes

> **Updated 2026-06-15.** The UI now drives every service. (Original snapshot is archived in §3 for the historical contrast.)

| Service | Endpoints the UI calls now |
|---|---|
| **iam-svc** | `auth/login`, `logout`, `refresh`, `register`, `change-password`, `auth/admin/staff-users` |
| **tenant-svc** | `admin/tenant` (GET), `admin/stores` (+`/{id}` PUT, `/status` PATCH), `stores/{id}/zones` (+ status), `admin/staff` (+DELETE), `onboarding*`, `platform/tenants` (+status), `storefront/config`, `storefront/stores` |
| **product-svc** | `admin/categories`(CRUD), `admin/products`(CRUD, `/variants`, `/stores`), `admin/import`, `catalog/products`(+id,+variants), `catalog/categories`, `catalog/variants/by-barcode` |
| **inventory-svc** | `admin/inventory/levels`, `admin/inventory/receive`, `inventory/availability` |
| **pricing-svc** | `prices/resolve`, `price-lists`(+items), `promotions`(+items, +storefront read), `vat-rates`(create/update) |
| **order-svc** | `orders`(GET/POST/`/mine`/`/{id}`/actions/returns), `gift-cards`(+redeem/reload/txns), `layaways`(+deposits/complete/cancel), `special-orders`, `pos/parked-sales`(+no-sale), `admin/orders/{id}/receipts` |
| **payment-svc** | `payments`(POS), `payments/online`, `by-order/{id}`(+refunds), `admin/cash/till-sessions`(+drops/x-report/close), `admin/cash/movements` |
| **customer-svc** | `customers`(list/add), `/{id}/loyalty`(+earn/redeem/ledger), `/{id}/store-credit`(+issue/redeem) |
| **notification-svc** | `admin/notifications/shortage-alerts` |
| **reporting-svc** | `on-hand`, `supply-demand`, `movement-stats` (all 3) |

The fresh orphan analysis (what is still **not** called) is in **§12**.

---

## 3. Gap A — Backend endpoints with zero UI ("orphaned" services) — *historical (project start)*

> ⚠️ This section is the **original** audit, kept for contrast. Most items below are now closed — see §6 progress and §12 for the current orphan list.

These are fully-built services that nothing in the app touches. They are untested through the product and represent either dead weight or missing screens.

### 🔴 Entire services with **no** UI

| Service | What it offers (all unused) | Impact |
|---|---|---|
| **customer-svc** | customer CRUD, lookup, addresses, **loyalty** (earn/redeem/adjust/ledger), **store-credit** (issue/redeem) | There is no customer record anywhere in the UI. Storefront checkout is anonymous; POS has no "attach customer". Loyalty & store-credit are completely unreachable. A `customer` API constant exists in `constants.dart` but is never used. |
| **purchase-svc** | suppliers, purchase-orders (+lines, submit), goods-receipts, intercompany-invoices, nominal-ledger | The **entire procurement / replenishment-in side is invisible.** Stock can only be added through the ad-hoc `inventory/receive` form — no PO, no supplier, no goods-receipt matching. A `purchase` constant exists but is never used. |
| **notification-svc** | `admin/notifications/shortage-alerts` | Shortage alerts are generated but never surfaced to a user. |

### 🟠 Services where the UI uses a tiny slice and ignores the rest

| Service | Used by UI | **Unused (no UI)** |
|---|---|---|
| **inventory-svc** | levels, receive, availability (3) | The ~100-endpoint `AdminResource`: planning/suggestions, transfers, move-orders, ABC analysis, safety-stock, cycle-counts, physical-inventory, costing-methods, accounting-periods, kanban-cards, ROP plans, serials, lot-genealogy, reason-codes, source-types, lot split/merge, expiring batches, lot grades, UOM conversions, PAR levels, zone-GL mappings, **picking rules**, movements/adjustments view. |
| **order-svc** | orders + lifecycle actions | **gift-cards, layaways, parked-sales, no-sale, special-orders, pos-log, pos stock-positions, receipts, returns** (`/{id}/returns` GET+POST). Returns/refunds have no screen. |
| **payment-svc** | `payments/online` | **POS payments** (`POST /payments` non-online), refunds (`by-order/{id}/refunds`), **cash management** (till-sessions, drops, X-report, Z-report, cash movements). No till/drawer UI at all. |
| **pricing-svc** | `prices/resolve` | **price-lists** (create/list/items/batch), **price-overrides**, **promotions**, VAT rates, VAT returns, product-VAT-categories, customer-VAT-status, tax-transactions. Pricing is effectively unmanaged from the UI — there is no screen to set a price; `prices/resolve` only reads. |
| **iam-svc** | login/refresh/logout/register/staff-users | `auth/me`, `change-password`, **POS sessions** (open/activity/close/list/sweep), `bootstrap/admin`. No "open POS session / clock-in" screen, no change-password screen. |
| **tenant-svc** | tenant, stores, staff, onboarding, platform, storefront | **Zones** (`stores/{id}/zones` full CRUD) — never created or managed in the UI. Also `inventory-config` (PUT/GET), store/zone **status** PATCH. |
| **reporting-svc** | on-hand | `supply-demand`, `movement-stats` (UI shows "Coming soon" placeholders). |

> **Most consequential orphan: Zones.** The whole domain model is `Tenant → Store → Zone`, and inventory batches live in a `(store, zone)`. But the UI never creates a zone (the onboarding wizard does not create zones either — verified). So every batch is received into a tenant with no managed zone structure, and the picking-rules engine that depends on zones is unreachable.

---

## 4. Gap B — UI that exists but is **not** wired (mock / dead front-end)

These screens render and look complete but do not talk to the backend, so they mislead.

| Screen | State | Detail |
|---|---|---|
| **POS — Cart** (`pos/cart_screen.dart`) | 🔴 Fully mocked | Barcode "scan" creates a line item with `name: 'Product ($sku)'` and `unitPrice: 0.00`. No catalog lookup (the backend `catalog/variants/by-barcode/{code}` endpoint is **never called**), no price resolve, no availability check. |
| **POS — Tender** (`pos/tender_screen.dart`) | 🔴 Fully mocked | `_due => 0.00 // TODO`. "Complete Sale" shows a snackbar "receipt printing…" and navigates home. Two `TODO`s admit it never posts to order-svc or payment-svc. No order, no payment, no receipt, no cash drawer. |
| **Storefront — Order history** (`storefront/orders_screen.dart`) | 🔴 Placeholder | Static text "Your orders load from order-svc." 23 lines, no call. Customers cannot see past orders. |
| **Admin — Reports** (`admin/reports_screen.dart`) | 🟠 2 of 3 placeholder | Supply/Demand and Movement-Stats render a "Coming soon" card showing the endpoint name; only On-Hand is wired. |
| **Storefront — Promo banner** (`product_list_screen.dart`) | 🟢 Cosmetic | Auto-rotating banner content is hard-coded placeholder (comment says "wire it to" promotions). The `promotions` API is unused. |

> **POS is the single biggest UI gap.** The entire in-store sales channel — which the architecture says shares `order-svc` with online checkout — is a non-functional shell. It does not look up products, prices, stock, customers, tax, or tender. By contrast the **storefront checkout is genuinely wired** (`order-svc/orders` → `payment-svc/payments/online`), so the POS gap is purely front-end.

---

## 5. Gap C — Cross-cutting / contract gaps

| # | Area | Finding |
|---|---|---|
| C1 | **Storefront pricing trust** | `storefront/cart_screen.dart` posts each line with a **client-supplied `unitPrice`** to `order-svc/orders`. Even though `prices/resolve` exists and is called for display, the order is placed on the client's number. If order-svc doesn't re-resolve server-side, this is a price-tampering hole. (Confirm `shelfj.order.pricing.enforce` is on.) |
| C2 | ✅ **Storefront customer identity** | RESOLVED — storefront now has register/login (iam self-signup), attaches the customer bearer, and the gateway derives the tenant from `X-Storefront-Tenant` for signed-in customers on the whitelisted storefront paths. Orders placed while signed in are bound to the authenticated `customer_id`, and `GET /orders/mine` powers real server-backed order history. See §9. |
| C3 | **`cart-svc` doesn't exist** | `constants.dart` defines a `cart` service constant, but there is **no `cart-svc` module** under `services/` and nothing calls it. Cart state is purely client-side (Riverpod). Either remove the constant or the architecture's cart-svc is unbuilt. |
| C4 | ✅ **Status toggles wired** | RESOLVED — both `PATCH stores/{id}/status` (store card chip) and zone status (zones dialog) are now callable from the admin app. |
| C5 | **Refund / return path absent end-to-end** | order-svc returns + payment-svc refunds exist; no UI initiates either. A cancelled/returned sale cannot be processed by staff. |
| C6 | **Idempotency** | Storefront checkout correctly sends `Idempotency-Key` headers. POS (when built) and admin order actions do not — worth standardising before POS is wired. |

---

## 6. Prioritised gap backlog

> **Progress (updated 2026-06-15):** ✅ closed — POS end-to-end (1), price enforcement confirmed already implemented (2), zone management (3), procurement (4), pricing management (5), returns & refunds (6), customer/loyalty/store-credit (7), cash management (8), placeholder reports (9), shortage-alerts surface (11), change-password + cart-svc cleanup (12/13), secondary order features (§8), **storefront customer auth + server-backed order history (§9)**, **promo-banner wiring (14)**, **store-status toggle**. ⏳ remaining — only the speculative inventory-svc ops (§10), which are deliberately deferred.

**🔴 P0 — flows that are broken/misleading or unsafe**
1. ✅ **POS sale** — wired cart→barcode lookup (`catalog/variants/by-barcode`) → price (`prices/resolve`) → order (`order-svc/orders` channel=POS) → tender (`payments`) → receipt dialog, with store selector + Idempotency-Key headers.
2. ✅ **Server-side price enforcement on checkout** (C1) — already implemented in `OrderService.placeOrder` via `pricingEnforce()`; keep the flag on in prod.
3. ✅ **Zone management UI** — add/edit/activate zones per store (tenant-svc zones CRUD).

**🟠 P1 — major business capability missing a front-end**
4. ✅ **Procurement**: suppliers + purchase-orders (+lines, submit) + goods-receipts screens under a new admin "Procurement" tab.
5. ✅ **Pricing management**: new admin "Pricing" tab — price-lists (+items via variant picker), promotions (auto-scoped ALL), VAT rates (create/edit).
6. ✅ **Returns & refunds** UI — return dialog on admin orders (order-svc returns + payment-svc refund against the original tender).
7. ✅ **Customer + loyalty + store-credit** UI — admin "Customers" tab with directory, add, loyalty earn/redeem + ledger, store-credit issue/redeem. ✅ storefront *customer auth* (C2) — register/login + **server-backed order history** now live (see §9).
8. ✅ **Cash management**: POS "Cash" tab — open till, X-report, cash drops, pay-in/out, close (Z-report with over/short).

**🟡 P2 — depth on existing screens**
9. ✅ Finished the 2 placeholder reports (supply-demand, movement-stats).
10. ⏳ **Deferred (speculative):** inventory-svc advanced ops (transfers, move-orders, cycle counts, physical inventory, ABC, safety-stock/ROP, kanban, lot-genealogy, costing, accounting-periods, serials, reason/source codes, picking rules). Decision: shelve until a concrete user need; not worth carrying as UI.
11. ✅ Shortage-alerts surfaced as a dashboard banner (notification-svc).
12. ✅ Change-password dialog (iam) in the admin account menu. ✅ **store-status toggle** — tap the status chip on a store card to activate/deactivate (`PATCH /admin/stores/{id}/status`), with a confirm dialog on deactivate. ⏳ `auth/me` still minor-pending (low value).

**🟢 P3 — cleanup**
13. ✅ Removed the unused `cart-svc` constant (C3); added missing `notification` constant.
14. ✅ **Promo banner wired** — the storefront offers carousel now renders the tenant's live promotions (`GET /pricing-svc/promotions`, newly whitelisted as a public storefront read), falling back to evergreen content when there are no active offers.

---

## 8. Secondary order-svc features — ✅ built

- ✅ **Gift cards** — issue + lookup-by-code + reload/redeem + transactions (admin "Sales" tab).
- ✅ **Layaways** — create (multi-item) + lookup-by-id + deposits/complete/cancel (admin "Sales" tab).
- ✅ **Special orders** — list + create + confirm/fulfil/cancel (admin "Sales" tab).
- ✅ **Parked sales / no-sale** — Hold / Resume / No-sale on the POS cart.
- ✅ **Printed receipts** — "Print receipt" action on admin orders (`POST /receipts`).
- ⏭️ POS-log / stock-positions reads — left unsurfaced (low value; data already visible elsewhere).

## 9. Storefront customer auth — ✅ closed (UI + backend)

- ✅ **Customer register / login / session** on the storefront (iam self-signup creates a CUSTOMER and returns tokens); signed-in state in the app bar; the customer bearer is attached to storefront requests.
- ✅ **Server-backed "my orders"** — built end-to-end:
  - **Gateway** (`JwtAuthFilter`): a verified customer token (tenant claim null) now has its tenant stamped from `X-Storefront-Tenant` **only** for the whitelisted storefront-customer paths (`POST /order-svc/orders`, `GET /order-svc/orders/mine`, `POST /payment-svc/payments/online`). Scoped deliberately so a customer token can never name a tenant for admin endpoints (covered by `customerTokenCannotNameTenantForAdminOrderList`).
  - **order-svc**: `placeOrder` binds `customer_id` from the **authenticated** identity when the caller is a CUSTOMER (never the request body); new `GET /orders/mine` lists orders filtered to `customer_id = authenticated userId` within the storefront tenant (reuses the existing `idx_orders_customer` index).
  - **Storefront UI**: `serverOrdersProvider` calls `/orders/mine` when signed in; `orders_screen` shows real, cross-device history with status chips + pull-to-refresh, and falls back to the device-local list (with a "sign in to sync" banner) for guests. New order invalidates the server list.
  - Tests: 2 new gateway unit tests (positive + negative); order-svc compiles + existing tests green.

## 11. POS "sell-at-ease" UX pass — ✅ built (2026-06-15)

Beyond raw API coverage, the POS was audited for *whether a cashier can actually sell fast*. Gaps closed:

- ✅ **Tap-to-add product grid** — a **Browse** button on the Sale screen opens a searchable product grid (POS-sellable + active products); one tap resolves the first variant + POS price and adds it. Removes the hard dependency on a working barcode for every item.
- ✅ **Attach a customer to the sale** — a customer bar (walk-in by default) with a search/pick dialog; the chosen `customerId` is sent on the POS order so it's attributed (enables loyalty/store-credit follow-up). Cleared automatically after each sale.
- ✅ **Cash quick-tender** — "Exact" + next-note denomination chips fill the tendered amount; change still computed live.
- ✅ **Receipt at completion** — the success dialog offers **Print** and (when the sale has a customer email) **Email**, recording a receipt against the order.
- ✅ **Line management** — swipe-to-remove a line; clearer empty-cart hint ("Scan a barcode or tap Browse").

### Payment depth — ✅ built (multi-tender)

POS is the heart of the system, so the tender step was rebuilt as a real multi-tender flow:

- ✅ **Split tender** — one sale across several part-payments; staged tenders show **Total due / Paid / Remaining**, and **Complete** unlocks only when the balance is cleared. Each tender is recorded as its own `POST /payments` against the single order.
- ✅ **Gift card as a tender** — enter the code → validates + shows balance (`GET /gift-cards/{code}`); the applied amount is capped at the card balance and the remaining due, and on completion the card is redeemed (`/gift-cards/{code}/redeem`) and a `GIFT_CARD` payment recorded.
- ✅ **Store credit as a tender** — for the attached customer; balance shown (`/customers/{id}/store-credit`), capped, redeemed (`/store-credit/redeem`) and recorded as a `VOUCHER` payment.
- ✅ **Cash with change** — per-cash-tender "given" vs "applied", with Exact + denomination quick-keys; total change summed on the receipt.
- ✅ **Order-level discount** — set on the Sale screen (clamped to subtotal), shown in a subtotal→discount→total breakdown, and passed as `discountAmount` on the order.

Still open (lower priority, not blocking a sale): **line-level** discounts at POS (order-level done), and a live per-item stock indicator on the product grid (would add a lookup per tile).

## 12. Fresh orphan audit (2026-06-15) — endpoints with no UI consumer

Re-derived by cross-referencing all 345 endpoints against every UI `dio` call. Orphans fall into two groups.

### 12a. Deferred "deep" modules (≈182 endpoints) — intentionally not surfaced

| Module | Orphan endpoints | Note |
|---|---|---|
| **inventory-svc `AdminResource`** | ~102 | Advanced WMS: planning/suggestions, transfers, move-orders, ABC, safety-stock, ROP plans, cycle-counts, physical-inventory, costing-methods, accounting-periods, kanban, serials, lot-genealogy, lot split/merge, reason/source codes, par-levels, zone-GL mappings, picking-rules. Plus `POST /admin/inventory/adjust`, `/batches*`, `/movements`, `/thresholds`, `/demand/*`. |
| **inventory-svc `ReservationResource`** | 6 | reservations: create/get/consume/release/batch. (Stock reservation is handled implicitly by order events today, not a UI flow.) |
| **product-svc `AdminResource` (PIM extras)** | ~74 | UOM classes/units/conversions, item-templates, variant cross-references, relationships, revisions, catalog-groups, container-types, attribute-groups, category-sets. Core product/category/variant CRUD + assortment + import **are** wired. |

These remain shelved until a concrete user need (consistent with the original recommendation). They are the bulk of the "no UI" surface.

### 12b. Actionable orphans (≈65 endpoints) — plausibly worth a UI next

Grouped by likely priority for a store/POS product:

> **Update 2026-06-15 (later):** ✅ **Cashier-safe POS catalog** built — `GET /catalog/products?channel=POS` now serves `sellable_pos` products (cashier-reachable), so the till's product grid no longer needs a manager. ✅ **POS redesigned as a two-pane supermarket till** — persistent category-filtered catalog (search + per-tile POS price + live stock dot) beside a live sale pane (qty steppers, swipe-remove, order discount, "Charge" total); collapses to a Browse sheet on phones. ✅ **Storefront tenant-suspension** enforced at the gateway (`TenantStatusGate`, cached `GET /storefront/active`, fail-open) — a deactivated tenant's shop returns 403.

**🟠 POS / cash control depth**
- `iam-svc` **POS sessions** — ✅ **built (2026-06-15).** Clock-in gate on the POS shell: a cashier opens a session bound to a store (`POST /auth/pos/sessions`), the terminal heartbeats every 4 min (`PUT /{id}/activity`) and on each sale, the store is fixed to the session, and **Clock out** ends it (`DELETE /{id}`). Sessions are restored after a refresh via `GET /auth/pos/sessions` (filtered to the signed-in cashier). `POST /sweep` stays a platform-admin maintenance op (not UI). ⚠️ See cashier-authz note below.
- `order-svc` **POSLog** — `POST/GET /admin/pos-log`, `GET /admin/pos-log/orders/{id}` (3): the POS transaction journal / electronic journal.
- `order-svc` `GET /admin/pos/stock-positions` (1): live on-hand for the POS product grid (the "stock badge" gap noted in §11).
- `payment-svc` `GET/POST /admin/cash/z-report` (2) standalone Z-report, and `GET /admin/cash/till-sessions/{id}` (1). The till **close** already returns Z data inline, so these are reporting niceties.
- `payment-svc` `GET /payments/{id}` (1).

**🟠 Customer / CRM depth** — ✅ **built (2026-06-15):** customer detail dialog now has **Edit** (`PUT /customers/{id}` — name/phone/dob/gender), **Anonymize** (`DELETE /{id}`, GDPR-confirm), and a full **Addresses** section (list/add/edit/delete via `/customers/{id}/addresses*`, with default-address + type). Remaining minor: `GET /lookup` (list+search covers it) and `POST /loyalty/adjust` (earn/redeem already wired).

**🟡 Tax / pricing depth**
- `pricing-svc` `GET /vat-return` (1) and `tax-transactions` GET/POST (2): VAT-return reporting — relevant for compliance.
- `pricing-svc` `price-overrides` POST/GET (2): manual per-line/variant price overrides.
- `pricing-svc` `customer-vat-status` (2), `product-vat-categories` (2), `GET /vat-rates/{code}` (1), `GET /price-lists/{id}` (1), `POST /price-lists/{id}/items/batch` (1, bulk price import).

**🟡 Catalog / procurement / tenant admin**
- `product-svc` **brands** CRUD (5) and variant `PUT/DELETE` (2): brand management + variant edit/delete (variant create/list are wired).
- `purchase-svc` `GET /goods-receipts` (list), `GET /purchase-orders/{id}`, `GET /suppliers/{id}` (3): detail/list reads (create flows are wired).
- `purchase-svc` `intercompany-invoices` (4) + `GET /nominal-ledger` (1): finance/accounting — likely deferred.
- `tenant-svc` `PUT /admin/tenant` (edit tenant settings), `inventory-config` PUT/GET (2), `GET /admin/stores/{id}`, `GET /stores/{id}/zones/{zoneId}` (detail reads) (5).

**🟢 Trivial**
- `iam-svc` `GET /auth/me` (1, whoami), `order-svc` `GET /admin/special-orders/{id}` and `GET /pos/parked-sales/{id}` (detail reads), `order-svc` `GET .../receipts` (list). `POST /bootstrap/admin` is intentionally not UI (platform setup).

> **⚠️ Cashier-authorization finding (surfaced 2026-06-15 while building POS sessions):** the POS reuses several **management-gated** `/admin/*` reads — `GET /tenant-svc/admin/stores`, `GET /product-svc/admin/products`, `/admin/products/{id}/variants` (the product grid + variant resolve). The shared `AdminAuthorizationFilter` requires a MANAGEMENT role (PLATFORM_ADMIN/OWNER/MANAGER) for any `/admin/` path, so a **plain CASHIER token cannot use the product grid** — as built, the POS is operable by a manager, not a pure cashier. Clock-in's **store list was repointed** to the cashier-safe `GET /tenant-svc/storefront/stores`; barcode scan, `prices/resolve`, `/orders`, `/payments`, `/customers`, gift-card & store-credit redeem are already cashier-reachable. **Next backend task:** a cashier-safe "POS catalog" read for the product grid (a `sellablePos` filter on `/catalog/products`, or relax the authz for those specific reads). Until then the tap-to-add grid needs a management role; barcode scanning works for cashiers.

> **Most defensible next builds:** (1) ✅ POS sessions / cashier clock-in, (2) ✅ cashier-safe POS catalog (`channel=POS`), (3) ✅ live stock on the grid, (4) ✅ storefront tenant-suspension, (5) ✅ customer detail/edit + addresses, (6) ✅ catalog-sheet CSV import. **Remaining:** VAT-return report, brands CRUD + variant edit/delete, POSLog journal, PO/supplier/GR detail reads, edit-tenant + inventory-config (admin breadth — see §12b).

## 13. Session changelog (2026-06-15 → 16) — what shipped beyond the original backlog

**🔐 Security — tenant deactivation enforced (was a real flaw):** a deactivated tenant's staff could still log in. Fixed end-to-end — tenant-svc emits `TenantStatusChanged` (transactional outbox); iam-svc keeps a `tenant_status` projection (`V6`) + consumer, and `login`/`refresh` now return **403 TENANT_INACTIVE** (+ revoke refresh tokens). The **gateway** (`TenantStatusGate`) also 403s the storefront for suspended tenants. Tests added (gateway + AuthIT).

**🛒 POS — real supermarket till:** clock-in/sessions, two-pane register, cashier-safe `channel=POS` catalog, per-tile price + live stock, **multi-tender** (split + gift-card + store-credit), order discount, receipts, and **catalog/no-price mode** (order-only checkout). Cashier-authz gap (§12b note) resolved via the cashier-safe catalog.

**🌐 Storefront:** server-backed order history (`/orders/mine`), customer auth, suspended-store screen, promo banner wired, router fix (storefront deep-link no longer hijacked by a stored admin session), and **full catalog mode** — when `showPrices=false` the price is hidden on **every** surface (listing/detail/cart/checkout/cart-bar) and **never fetched** from the API; checkout becomes an order request (no online payment).

**🗂️ Admin:** customer **detail/edit + addresses + anonymize**; store status toggle; **Catalog Sheet CSV importer** (Category/Product/Pack Size/SKU) with validate → inline-correct/re-upload → import, and **New vs Override** (backend `mode=ADD|REPLACE`, upsert-by-SKU: reuse product by name+category, replace variant by SKU).

**🚢 Deployment (production-grade):** the **web UI is now a Docker service** (`shelf-app`, non-root nginx, gzip + security headers + SPA fallback, host port **8088**, API base configurable via `--dart-define`); all service images run **non-root** (uid 999); `Dockerfile.web`, `.dockerignore`, `scripts/redeploy.sh` (full clean redeploy incl. UI) and `scripts/run-web.sh` (dev) added.

**Still open:** task **#20** (VAT-return + brands CRUD + variant edit/delete); the rest of §12b; §10/§12a deferred modules. Ops: `pricing.enforce` still `false` (catalog orders record `unitPrice 0`); k6 regression not yet re-run against the new flows.

## 10. Formally deferred (not building)

The ~100 speculative inventory-svc endpoints (transfers, move-orders, cycle counts, physical inventory, ABC, safety-stock/ROP, kanban, lot-genealogy, costing, accounting periods, serials, reason/source codes, picking rules) and minor reads (POS-log, store-status toggle, `auth/me`, promo-banner wiring). Recommend surfacing per concrete user need rather than carrying speculative UI.

> The previously-flagged backend gap (a customer-scoped orders endpoint) is now **built** — see §9.

---

## 7. Recommendation

The backend has been built well beyond what any user can reach. Two divergent risks follow:

- **Front-end debt:** POS, returns, procurement, pricing admin, and customer/loyalty are entirely missing UIs for shipped, presumably-tested-only-by-k6 backends.
- **Backend over-build:** ~90 % of endpoints have never been driven by the product. Much of inventory-svc's `AdminResource` (kanban, ABC, lot-genealogy, costing, accounting-periods) may be speculative — decide per-feature whether to surface or shelve, so it isn't carried as untested liability.

Suggested next step: treat **POS end-to-end** and **server-side pricing on checkout** as the immediate sprint (P0), then pick **one** orphaned domain per sprint (procurement → returns → pricing-admin → customer/loyalty), each closing both the UI gap and giving its backend its first real exercise.
