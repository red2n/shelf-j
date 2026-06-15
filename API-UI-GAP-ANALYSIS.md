# Shelf-J — API ↔ UI Gap Analysis

> **Date:** 2026-06-14
> **Method:** Fresh code-level audit. Every `@GET/@POST/@PUT/@DELETE/@PATCH` resource method across `services/*/api/` was enumerated and cross-referenced against every HTTP call the Flutter app (`frontends/shelf-app/lib`) actually makes. This document is derived only from source code, not from any prior gap notes.
> **Scope:** backend REST surface vs. the single front-end (`shelf-app`, which hosts Admin + Platform + Storefront + POS in one Flutter app).

---

## 1. Headline numbers

| Metric | Count |
|---|---|
| REST endpoints defined across the 12 services | **~344** |
| Distinct endpoints the UI actually calls | **~30** |
| **Backend surface with no UI consumer** | **~90 %** |
| Services with a `cart-svc` / `customer-svc` constant in the app but **no calls** | 2 |
| Whole front-end areas that are visually present but **not wired to any API** | POS (100 %), Storefront order history, 2 of 3 reports |

The backend is far ahead of the front-end. The risk is not "missing API" — it is a large, untested, unexercised backend surface plus several UI flows that *look* finished but are mock-only.

---

## 2. What the UI actually consumes

Confirmed live calls, by service:

| Service | Endpoints the UI calls |
|---|---|
| **iam-svc** | `auth/login`, `auth/logout`, `auth/refresh`, `auth/register`, `auth/admin/staff-users` |
| **tenant-svc** | `admin/tenant`, `admin/stores` (+`/{id}`), `admin/staff` (+`/{id}`), `onboarding`, `onboarding/tenants`, `onboarding/stores`, `platform/tenants` (+`/{id}/status`), `storefront/config`, `storefront/stores` |
| **product-svc** | `admin/categories`(+id), `admin/products`(+id, +variants, +stores), `admin/import`, `catalog/products`(+id,+variants), `catalog/categories` |
| **inventory-svc** | `admin/inventory/levels`, `admin/inventory/receive`, `inventory/availability` |
| **pricing-svc** | `prices/resolve` **only** |
| **order-svc** | `orders` (GET/POST), `orders/{id}/{action}` (confirm/cancel/fulfil/void) |
| **payment-svc** | `payments/online` **only** |
| **reporting-svc** | `admin/reports/inventory/on-hand` **only** |

That's it. Everything below is defined and shipped on the backend but has **no front-end at all**.

---

## 3. Gap A — Backend endpoints with zero UI ("orphaned" services)

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
| C2 | **Storefront is anonymous** | The storefront Dio is explicitly *tokenless* (stamps only a tenant header). There is no customer login/register flow in the storefront, so orders cannot be tied to a customer, and order-history (Gap B) can never work without an identity. customer-svc registration endpoints are unused. |
| C3 | **`cart-svc` doesn't exist** | `constants.dart` defines a `cart` service constant, but there is **no `cart-svc` module** under `services/` and nothing calls it. Cart state is purely client-side (Riverpod). Either remove the constant or the architecture's cart-svc is unbuilt. |
| C4 | **No PATCH for status toggles** | tenant-svc exposes `PATCH stores/{id}/status` and zone status, but the UI never calls them — stores/zones can't be deactivated from the app. |
| C5 | **Refund / return path absent end-to-end** | order-svc returns + payment-svc refunds exist; no UI initiates either. A cancelled/returned sale cannot be processed by staff. |
| C6 | **Idempotency** | Storefront checkout correctly sends `Idempotency-Key` headers. POS (when built) and admin order actions do not — worth standardising before POS is wired. |

---

## 6. Prioritised gap backlog

> **Progress (updated 2026-06-14):** ✅ closed — POS end-to-end (1), price enforcement confirmed already implemented (2), zone management (3), procurement (4), pricing management (5), returns & refunds (6), customer/loyalty/store-credit (7), cash management (8), placeholder reports (9), shortage-alerts surface (11), change-password + cart-svc cleanup (12/13), storefront order history (device-local stand-in). ⏳ remaining — storefront customer auth (server-backed order history), promo-banner wiring (14), store-status toggle, and the speculative inventory-svc ops + secondary order features listed under §8.

**🔴 P0 — flows that are broken/misleading or unsafe**
1. ✅ **POS sale** — wired cart→barcode lookup (`catalog/variants/by-barcode`) → price (`prices/resolve`) → order (`order-svc/orders` channel=POS) → tender (`payments`) → receipt dialog, with store selector + Idempotency-Key headers.
2. ✅ **Server-side price enforcement on checkout** (C1) — already implemented in `OrderService.placeOrder` via `pricingEnforce()`; keep the flag on in prod.
3. ✅ **Zone management UI** — add/edit/activate zones per store (tenant-svc zones CRUD).

**🟠 P1 — major business capability missing a front-end**
4. ✅ **Procurement**: suppliers + purchase-orders (+lines, submit) + goods-receipts screens under a new admin "Procurement" tab.
5. ✅ **Pricing management**: new admin "Pricing" tab — price-lists (+items via variant picker), promotions (auto-scoped ALL), VAT rates (create/edit).
6. ✅ **Returns & refunds** UI — return dialog on admin orders (order-svc returns + payment-svc refund against the original tender).
7. ✅ **Customer + loyalty + store-credit** UI — admin "Customers" tab with directory, add, loyalty earn/redeem + ledger, store-credit issue/redeem. ⏳ storefront *customer auth* (C2) still pending → would upgrade order history from device-local to server-backed.
8. ✅ **Cash management**: POS "Cash" tab — open till, X-report, cash drops, pay-in/out, close (Z-report with over/short).

**🟡 P2 — depth on existing screens**
9. ✅ Finished the 2 placeholder reports (supply-demand, movement-stats).
10. ⏳ **Deferred (speculative):** inventory-svc advanced ops (transfers, move-orders, cycle counts, physical inventory, ABC, safety-stock/ROP, kanban, lot-genealogy, costing, accounting-periods, serials, reason/source codes, picking rules). Decision: shelve until a concrete user need; not worth carrying as UI.
11. ✅ Shortage-alerts surfaced as a dashboard banner (notification-svc).
12. ✅ Change-password dialog (iam) in the admin account menu. ⏳ store-status toggle (zone status done) and `auth/me` still minor-pending.

**🟢 P3 — cleanup**
13. ✅ Removed the unused `cart-svc` constant (C3); added missing `notification` constant.
14. ⏳ Wire promo banner to `promotions` or mark it static (cosmetic).

---

## 8. Secondary order-svc features — ✅ built

- ✅ **Gift cards** — issue + lookup-by-code + reload/redeem + transactions (admin "Sales" tab).
- ✅ **Layaways** — create (multi-item) + lookup-by-id + deposits/complete/cancel (admin "Sales" tab).
- ✅ **Special orders** — list + create + confirm/fulfil/cancel (admin "Sales" tab).
- ✅ **Parked sales / no-sale** — Hold / Resume / No-sale on the POS cart.
- ✅ **Printed receipts** — "Print receipt" action on admin orders (`POST /receipts`).
- ⏭️ POS-log / stock-positions reads — left unsurfaced (low value; data already visible elsewhere).

## 9. Storefront customer auth — ✅ partial (UI), backend gap remains

- ✅ **Customer register / login / session** on the storefront (iam self-signup creates a CUSTOMER and returns tokens); signed-in state in the app bar; the customer bearer is attached to storefront requests.
- ⛔ **Server-backed "my orders"** is **not** achievable from the UI alone: `GET /orders` is not a storefront-public path, resolves the tenant from the JWT (customer tokens carry `tenantId=null`), and has no per-customer filter. True customer order history needs a **backend** customer-scoped orders endpoint (tenant via storefront header + filter by the authenticated customerId). Until then, storefront order history stays **device-local**.

## 10. Formally deferred (not building)

The ~100 speculative inventory-svc endpoints (transfers, move-orders, cycle counts, physical inventory, ABC, safety-stock/ROP, kanban, lot-genealogy, costing, accounting periods, serials, reason/source codes, picking rules) and minor reads (POS-log, store-status toggle, `auth/me`, promo-banner wiring). Recommend surfacing per concrete user need rather than carrying speculative UI.

---

## 7. Recommendation

The backend has been built well beyond what any user can reach. Two divergent risks follow:

- **Front-end debt:** POS, returns, procurement, pricing admin, and customer/loyalty are entirely missing UIs for shipped, presumably-tested-only-by-k6 backends.
- **Backend over-build:** ~90 % of endpoints have never been driven by the product. Much of inventory-svc's `AdminResource` (kanban, ABC, lot-genealogy, costing, accounting-periods) may be speculative — decide per-feature whether to surface or shelve, so it isn't carried as untested liability.

Suggested next step: treat **POS end-to-end** and **server-side pricing on checkout** as the immediate sprint (P0), then pick **one** orphaned domain per sprint (procurement → returns → pricing-admin → customer/loyalty), each closing both the UI gap and giving its backend its first real exercise.
