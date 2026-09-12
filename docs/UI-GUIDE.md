# Shelf-J — UI Guide

> A screen-by-screen, persona-by-persona tour of the interface: what a user sees and can do on every screen, not how it's built. Pair this with [README.md](../README.md) (the product tour these screens serve) and [docs/API-GUIDE.md](API-GUIDE.md) (the API each screen calls). For the underlying app structure (state management, routing internals, networking), see [docs/ARCHITECTURE.md §13](ARCHITECTURE.md#13-the-frontend-shelf-app).

One application serves four experiences — Platform Admin, Tenant/Store Admin, POS, and the public Storefront — each gated by the signed-in user's role, sharing one consistent look, navigation pattern, and set of conventions.

## Table of contents

- [Persona & role model](#persona--role-model)
- [1. Auth](#1-auth)
- [2. Onboarding](#2-onboarding)
- [3. Platform Admin](#3-platform-admin)
- [4. Tenant / Store Admin](#4-tenant--store-admin--the-largest-console)
- [5. POS](#5-pos--cashier-facing-terminal)
- [6. Storefront](#6-storefront--public-customer-facing-shop)
- [7. Cross-cutting UX / product features](#7-cross-cutting-ux--product-features)
- [8. Full route reference](#8-full-route-reference)

---

## Persona & role model

A signed-in user's role decides both where they land and what they can reach:

| Role | Persona | Notes |
|---|---|---|
| `PLATFORM_ADMIN` | Platform Admin | Global superuser for the SaaS operator; never needs onboarding; confined to the Platform Console |
| `OWNER` | Tenant Admin | Business owner; granted automatically when a new tenant/store is created |
| `MANAGER` | Tenant Admin | Also treated as "admin" for routing purposes |
| `STOREKEEPER` | Warehouse operator | Admin shell restricted to **Inventory** + **Stores** (zones); home is Inventory |
| `CASHIER` | POS | Access to the POS only (till open is staff-allowed; close/Z-report stays manager+) |
| `CUSTOMER` | Storefront | Default role on self-registration; also the fallback/default persona |

> **Roles that map to real work:** `STOREKEEPER` can call `/admin/inventory/**` and the supporting store/zone/tenant reads (backend `AdminAuthorizationFilter` staff-admin tier). They cannot manage staff, pricing, catalog, or reports. `CASHIER` can open a till under `/admin/cash/**`; closing the till and cash drops remain manager+.

**Routing rules:**
- Unauthenticated users land on the sign-in screen (a separate one for the Platform Console vs. everyone else).
- The public storefront is reachable by anyone — guest or signed in — and is never hijacked by the login/onboarding/platform redirects.
- Signed-in Owner/Admin users with no business set up yet are routed straight into onboarding until it's complete.
- A Platform Admin is confined to the Platform Console; everyone else is blocked from it. Non-admins are blocked from the Admin Console; pure customers are blocked from the POS.
- Each role has a home screen it lands on right after signing in: incomplete onboarding → the setup wizard; platform admin → Platform Overview; admin → Dashboard; cashier → the POS Sale screen; everyone else → the Storefront.

**Shared navigation chrome:** every console uses the same responsive shell (`AdaptiveNavShell`) — a persistent side rail with icon/label toggle on tablet/desktop/web (≥800px). Below that, it collapses per shell: Admin (11 destinations) and the Platform Console fall back to a slide-out drawer, while Storefront (Shop/Cart) and POS (Sale/Tender/Cash) — both well within Material's 3–5-destination range — use a persistent bottom navigation bar instead, per Material 3's compact-width guidance. There's no separate "mobile app" vs. "web app" — it's one responsive experience throughout.

---

## 1. Auth

| Screen | What the user does |
|---|---|
| **Sign in / Register** | A single card that toggles between "Sign in" and "Create account." Registering asks for email, optional phone, and a password (min 8 characters); on success the account gets the Customer role and, if it's an Owner-to-be, is routed straight into onboarding. Errors (bad credentials, duplicate email, network failure) show as friendly, translated messages. |
| **Platform Console Sign-in** | A deliberately separate sign-in screen for platform operators — a tenant staff credential can't be used here, and vice versa. Email + password only; no self-registration. |

**Flow:** a new business owner registers → immediately lands in the onboarding wizard, since they have no business set up yet.

---

## 2. Onboarding

A two-step wizard, shown full-screen right after registration, with a visible step indicator.

**Step 1 — Business details:** business name (required), legal/registered name (optional), country (India / US / UK / Singapore / UAE), currency (auto-suggested from the country, editable). Submitting this creates the business.

**Step 2 — First store:** store name, a store code (auto-uppercased, e.g. `STR-001`), type (Retail Store or Warehouse), address line, city, postal code, country, timezone (matched to the supported countries). Submitting this creates the store, grants the Owner role, and lands the user on the Admin Dashboard.

**Flow:** Register → Business details → First store → Admin Dashboard. Adding *more* stores/zones or inviting staff happens afterward, from the Admin Console's Stores and Staff screens — it isn't part of the initial wizard.

**Alternate path — assisted onboarding:** a Platform Admin can set a tenant up on the business's behalf from the Platform Console's Tenants screen (see below) — a different, 2-step dialog (owner credentials, then business + store together) ending in an explicit "share these credentials with the owner" hand-off screen.

---

## 3. Platform Admin

**Persona:** the SaaS operator's own staff — monitors and manages the businesses (tenants) using the platform. Not a store/retail persona.

**Home — Platform Overview:** the signed-in admin's email and a "Platform Admin" badge; three status cards (tenant management, one-time bootstrap completion, service health); a short explainer of how tenant onboarding works; and a reference card of key platform routes. This dashboard reads more like an internal ops/runbook page than a customer-facing metrics dashboard — there are no tenant counts, growth charts, or usage metrics here (those live on the Tenants screen instead).

| Screen | What the user does |
|---|---|
| **Platform Overview** | See above. |
| **Tenants** | A table (wide) / card list (narrow) of every business on the platform: name, legal name, country, currency, status, created date. A per-tenant menu to **activate/deactivate** (with a confirmation explaining that a deactivated tenant and all its users immediately lose access — this is what triggers the storefront's "currently unavailable" notice). An **"Onboard New Tenant"** button opens a 2-step dialog: create the owner's login, then the business + first store in one form; success shows a "tenant is live" confirmation with the owner's email to hand off. |

**Flows:**
- *Monitor tenants:* Tenants list → inspect status → activate or deactivate.
- *Assisted onboarding:* Tenants screen → "Onboard New Tenant" → owner credentials → business + first store → hand off.

**Notable features:** two independent, hard-separated login surfaces (platform vs. store) so credentials never cross over; deactivating a tenant immediately locks out its storefront and (implicitly) its admin/POS logins.

---

## 4. Tenant / Store Admin — the largest console

**Persona:** business owners, managers, and store staff. Covers catalog, inventory, purchasing, pricing, staff, stores/zones, and reporting for the whole business — all stores at once, not scoped to a single store.

**Home — Dashboard:** the business's name and currency in the header; a low-stock alert banner (up to 5 SKUs shown, "…and N more") when anything is at or below its threshold; four stat cards (Revenue, Orders, Low Stock count, SKU count); a horizontally-scrolling "Your Stores" strip (name, code, active/inactive dot, warehouse vs. store icon); and a "Recent Orders" list (channel icon for POS vs. Online, status badge, total) linking to the full Orders screen. Pull-to-refresh reloads everything.

Left-hand navigation: **Dashboard · Catalog · Inventory · Stores · Orders · Procurement · Pricing · Reports · Customers · Sales · Staff.**

### 4.1 Catalog (3 tabs)

| Tab | What the user does |
|---|---|
| **Products** | Search/filter by category; a table or card list showing name, description, category, Online/POS sellability, and status. **New Product**: name, description, category, Online/POS sellability toggles. Per-product actions: **View Variants**, **Sold at stores**, **Product image**, **Delist**. |
| ↳ *Variants* | Add/view SKUs under a product: SKU code, barcode (with a camera scan button to fill it in), unit (e.g. "1kg"), and a **Set price** action per variant. Each variant also has **Allergens and origin**: a *Food product* switch; for food, the fourteen regulated allergens each marked **No / May contain / Contains** under a status banner (*Declared*, *Not yet declared — on the allergen gaps list*, or *Not yet marked as food*), and the ingredients as printed; country of origin and origin detail; the age-restriction category the till checks; and how the item is sold — each, weight, volume or length, catch weight, net content and its unit, and tare. **Saving never declares allergens by accident.** An empty declaration is a positive statement that the product contains none of the fourteen, so allergens are sent only after ticking *I have checked … against the label*, and changing any answer afterwards un-ticks it. Every compliance field goes on every save, because the update replaces them all and a field left out would be erased. |
| ↳ *Sold at stores* | Toggle "sell at all stores" (including future new stores) vs. hand-picking specific stores by checkbox — controls where a product shows up on POS/storefront. |
| ↳ *Product image* | Upload (JPEG/PNG/WebP, size-limited) or remove the image shown on the storefront listing and product page. |
| **Categories** | A flat list with one level of parent/child nesting. Create/Edit: name + optional parent. **Deactivate** hides a category from product-assignment dropdowns without deleting it. |
| **Import** | Upload a supplier catalogue CSV; the app parses it client-side and previews row count, distinct products/categories/stores detected, and whether price/quantity columns are present — before anything is submitted. Lets the admin map each CSV store name to a real store and pick one destination store to receive stock into, in either **"add"** or **"replace existing"** mode. A built-in "expected format" help dialog documents the CSV schema. One import creates categories, products, prices, and stock receipts together, then reports created counts and itemized errors per category (catalog / stock-receipt / price). |

### 4.2 Inventory (5 tabs)

| Tab | What the user does |
|---|---|
| **Levels** | Search by product/SKU/ID; a "Low stock" filter chip (uses configured reorder thresholds when set); on-hand/reserved/available quantity per product per store with a Low/OK badge; infinite scroll. Per-row actions: **Adjust stock** (signed delta + reason) and **Set reorder level**. |
| **Batches** | Filter by store, zone, and material status (Available / Quarantine / Rejected / Hold); batch number, variant, remaining/received qty, zone, expiry date, and grade. **Change material status** per batch; banner for batches expiring within 30 days. |
| **Transfers** | Inter-store transfer orders: create (from/to store + variant + qty), list, **Ship** / **Receive** / **Cancel** by status. |
| **Movements** | Append-only stock movement ledger (receipts, sales, adjustments, transfers), filterable by store. |
| **Thresholds** | List reorder levels per store/variant; create or edit threshold (+ optional max qty). |
| **Receive Stock** *(top-level action)* | Pick a store (and zone within it), then either scan a barcode with the camera or enter a variant manually; enter quantity, optional batch number, cost price, and expiry date. |

### 4.2a Food safety (2 tabs for a storekeeper, 4 for a manager)

Temperature monitoring and HACCP checks for one store at a time (a store picker appears when there is more than one; a store-bound member of staff sees only their own stores). Visible to storekeepers as well as managers — taking a chiller reading is shop-floor work.

| Tab | What the user does |
|---|---|
| **Today** | Every monitoring point at the store, overdue first, then due, then done, with counts of overdue, due-soon and open failures. Each row shows the check, its limit, how often it is due, the last reading and any failures still without a corrective action. **Record** opens the check dialog: a reading in °C with a live "within / outside the limit" preview (the saved result is always the server's), or Passed/Failed for a checklist, plus notes. A failing check is saved as a failure at once and the dialog then asks **what was done** and what happened to the food; "Record later" leaves the failure open and visible, it does not skip it. A retried Save after a network error reuses the same Idempotency-Key, so a reading is recorded once. |
| **Diary** | Today / 7 days / 28 days, an "Open failures only" filter, and **Export CSV** (UTC timestamps, reading, limits, result, corrective-action count, notes). An open failure has a **Record action** button. |
| **Setup** *(manager)* | Add a check (name, check type, stricter limits, how often), **Edit**, and **Switch off / on** with a required reason. The dialog states the type's limit — "Legal limit ≤ 8.00 °C" for a statutory one — and a laxer limit is refused with the server's message. |
| **Reviews** *(manager)* | **Sign off the last 28 days** with notes; each review lists the checks, failures and failures still open at the moment it was signed. |

Alerts: a failed check and a missed check both reach the store's devices through notification-svc, once per event.

### 4.2b Recalls

Withdrawals and recalls across every store. Visible to storekeepers as well as managers — pulling stock off the shelves is shop-floor work.

| Part | What the user does |
|---|---|
| **List** | Open / Closed / Cancelled / All. Each recall shows its reference, a *Recall* or *Withdrawal* badge, the hazard, when it opened, how much is held, and how many stores still have to act. A list that fails to load shows the error, never *No open recalls*. |
| **Open a recall** *(manager)* | Withdrawal or recall; the notice reference, where it came from and its own reference, the hazard, what's wrong, and — for a recall — the notice for customers, which is required. Affected items are added one at a time: product and variant, optionally a lot, and a date range (YYYY-MM-DD). **Take off sale** holds every pack in scope at every store at once, and opens the recall. |
| **A recall** | What's wrong and the customer notice; the affected items in the words on the pack (*Lot L1, dated 2026-10-01 or later*); then each store with what was taken off sale and whether it is still to do. Each held batch shows its lot, date and quantity, and whether it is certainly in scope or held because its lot or date isn't recorded — those have **Not affected**, which asks what the pack shows and puts it back on sale. **Record what was found** asks how much was on the shelves, what became of it (*Held for collection*, *Returned to supplier*, *Destroyed*) and, for a recall, whether the notice is displayed at the tills; returned or destroyed stock leaves the books. A storekeeper sees these only for their own stores. |
| **Close / Cancel** *(manager)* | **Close recall** asks for close-out notes; closing too early names the stores still holding recalled stock. **Cancel recall** asks why it was opened in error and puts its stock back on sale. |

### 4.2c Age checks (management)

The register a licensing officer asks to see. Filters: one store or all, a date range (the last 30 days by default), and all / refusals / passed. Three counts — checks, sales that went ahead, refused — with the refusals broken down by reason, then the register itself: each check's category, minimum age and country (marked when the age was the store's own stricter policy), when it was made, and either why it was refused or what was shown. Nothing on the screen edits or deletes a record: the server keeps it append-only, and an empty register is shown with the observation that a shop selling restricted items which has never refused anyone has not been checking.

### 4.3 Stores

- A list of stores/warehouses: name, code, city/country, "Prices shown" vs. **Catalog mode** badge, active/inactive toggle.
- **Add Store**: name, code, type (Retail Store / Warehouse), address, country, timezone, and a **"Show prices on storefront"** toggle (turning it off puts the store into Catalog mode — see [§7](#7-cross-cutting-ux--product-features)).
- **Edit Store**: the same fields plus **accepted payment methods** (Cash / Card / UPI / Wallet — at least one must stay enabled; this drives what's offered at POS tender and storefront checkout).
- **Zones dialog** (per store): list of zones/aisles; add/edit a zone's name, code, and type (Aisle, Rack, Shelf, Cold Room, Back Store, Receiving, Display) — the store's internal location map.
- **Deactivate store**, with confirmation.
- **Instruments** (per store) — the weighing-instrument register (Weights and Measures Act 1985). Every scale the store weighs for trade on, with its standing derived from its history — *Certified for trade*, *Never verified*, *Failed its last check*, *Repaired since last verified*, *Re-verification overdue*, *Out of service*, *Retired* — and, for a manager, **Register scale**, **Record verification…** (initial verification, re-verification, trading-standards inspection, or a repair, which is never a pass and takes the scale out of trade until it is verified again), **History**, **Edit**, and the status actions. Retirement asks for confirmation and is final. A labelling scale carries the label scheme the till reads its barcodes with. Until a scale is registered and verified, the till cannot sell anything by weight at that store.


### 4.4 Orders

- One list covering **both channels** (Online and POS), with channel and status filter chips (Pending/Confirmed/Fulfilled/Cancelled) and infinite scroll.
- Per-order actions: **Confirm**, **Mark fulfilled**, **Collect payment**, **Cancel**, **Return / Refund**, **Print receipt**. *Mark fulfilled* opens **Hand over** (SJ-D35): each line with what is still outstanding and a quantity going now — leave them as they are to hand over everything, lower one to hand over part; the order shows **PARTIALLY_FULFILLED** (a filter of its own) until every line is complete, and *Mark fulfilled* is offered again for the rest. More than is outstanding is stopped in the dialog, and the server's refusal is shown in words. A till sale arrives here already **Fulfilled** — it is handed over when it is paid for — so *Mark fulfilled* is only ever offered for online and delivery orders.
- **Collect payment**: for orders where money wasn't captured online (cash-on-delivery/at-pickup, or a Catalog-mode order) — shows amount already collected vs. outstanding and lets staff record the payment method and capture the balance at handover.
- **Return / Refund**: pick quantities to return per line, a reason, and a refund method (card/original tender, cash, or store credit); a live refund-total preview; posts the refund automatically against the original payment, or credits store credit.
- **Cancel order**, with confirmation.

### 4.5 Procurement (2 tabs)

| Tab | What the user does |
|---|---|
| **Purchase Orders** | List with status. **Create PO**: supplier, currency, expected delivery date. **PO detail**: add lines (variant, quantity, cost, tax rate) with a running gross total; **Submit**; then **Receive goods** — confirm actual received quantities per line once the delivery arrives (updating stock immediately). **Split deliveries are shown line by line**: each line carries what is still outstanding, in amber, so a buyer chasing a supplier can see *what* is missing rather than only that something is — which is all a `PARTIALLY_RECEIVED` badge says. The receive button reads "Receive balance" once part has arrived, and appears only while the order is actually receivable; it used to offer itself on cancelled and already-received orders, where it could only ever produce an error. **Close short** abandons an undelivered balance with a required reason, for the order a supplier is never going to complete. **Spend authority**: submitting an order above the buyer's own ceiling does not go to the supplier — the badge reads `PENDING_APPROVAL` in amber, and the confirmation says "above your spend authority, sent for approval" rather than claiming the order was placed. Someone with the authority then **Approves** or **Rejects** it from the same detail dialog; a rejection needs a reason and sends the order back to DRAFT to be corrected. Money is formatted per its own currency rather than to two decimal places, so a JPY order reads ¥3,702 and not ¥3,702.00; a unit price keeps its own precision, because 0.0125 per screw is a real trade price and rounding it to 0.01 misreports the line by a quarter. |
| **Invoices** | **The three-way match.** One row per invoice line showing *ordered / received / invoiced* side by side, with both unit prices when they differ (`2.50 → 2.75`). Variances are shown as sentences a buyer can act on — "Billed for more than arrived", "Charged above the agreed price" — rather than the API's constants. **Flagged invoices sort first and open expanded**: the exceptions are the entire point of the control, and a list in date order buries them behind the invoices nobody needs to read. A variance the buyer has to click to discover is one that waits until the payment run. |
| **Suppliers** | List of suppliers. **Add supplier**: name, country, currency, "VAT registered" checkbox, payment terms. **Edit** (managers): the same form prefilled, so a wrong currency, VAT number or terms can be corrected; the server refuses a currency change while a purchase order against the supplier is open and the dialog says so. |

### 4.6 Pricing (4 tabs)

| Tab | What the user does |
|---|---|
| **Price Lists** | Create a list scoped to a channel (All / Online / POS); open a list to add per-variant items (price + minimum quantity). Each row carries a **Stop / Start** control: a price list decides what customers are charged, and until this existed a decimal in the wrong place could only be corrected by editing the database. Stopping asks for a reason — recorded against the user's name — and warns what it costs: if nothing else prices those items they cannot be sold until it is started again, which is the safe answer to a price nobody agreed. Orders already placed keep what they were charged. |
| **Promotions** | Create a promotion across all six types the engine understands: % or amount off each item, % or amount off the basket, spend-and-save, and buy-X-get-Y. Plus a coupon code (blank means it applies on its own), a priority (lower runs first — which of two overlapping offers wins is now a decision rather than an accident), total and per-customer usage caps, and a "cannot be combined" switch that suppresses every promotion after it. The BOGO fields appear only for a BOGO, and the value field disappears there, because a BOGO is described by its three quantities and a number in a value box would mean nothing. The list shows each promotion's own summary — "Buy 2, get 1 free", "£5 off over £100" — with its code, and flags an exclusive one inline because it changes what every other promotion does. Applies to all products by default. Each row carries a **Stop / Start** control, for the same reason: `endsAt` is optional, so a promotion created without one runs forever, and the badge saying "Active" used to offer no way to change that. A reason is required in both directions — a trail that records only why things were stopped answers the easier half of "who turned this back on?". |
| **VAT Rates** | Create/edit tax rates: code, name, percentage, and an "Exempt" flag. |
| **VAT Return** | The nine boxes of a UK VAT return for a chosen period, with quick ranges for this quarter and the last 90 days. **Only boxes 1, 3, 5 and 6 are computed** — from the tax transactions recorded at sale, not re-derived from orders, so an amended order cannot silently change a figure already filed. **Boxes 2, 4, 7, 8 and 9 are hardcoded zero** (SJ-D39). Box 4 is input VAT reclaimed on purchases: the figures exist, but in purchase-svc's supplier invoices, and nothing carries them across. A business filing this as-is pays its output VAT in full and reclaims none of it. **The screen now says so on its face:** boxes 2, 4, 7, 8 and 9 show a dash and "Not computed" rather than 0.00, under a red caveat that box 5 is overstated by the VAT the business may reclaim and the return is not fit to file. |

### 4.7 Reports

A sidebar (wide) / chip selector (narrow) between fifteen read-only tables, each with a refresh button and, where it has rows, a **CSV export**. Reports that cover a period carry a from/to date bar; those that group carry grouping chips. The sidebar scrolls — the list outgrew a laptop window, and a plain column does not merely hide the overflow but makes it unreachable.

*Business-wide, from reporting-svc:*

1. **On-Hand Inventory** — total units on hand per store/variant.
2. **Sales Revenue** — orders, gross, refunded, and net revenue by currency. Date range.
3. **Sales by Day** — daily revenue buckets. Date range.
4. **Supply / Demand Netting** — on-hand + in-transit → net available, per store/variant.
5. **Movement Statistics** — stock in / out / net per period, per store/variant.

*From the service that owns the data:*

6. **Low Stock** (inventory-svc) — items below their own reorder level, worst shortfall first. Names the **signal** that bound each row (THRESHOLD / SAFETY_STOCK / REORDER_POINT), so a manager can see *why* an item is flagged and therefore what to change.
7. **Stock Valuation** (inventory-svc) — holding value on the configured FIFO or AVERAGE basis, grouped by store or variant. Stock carrying no cost is reported **separately and excluded** from the value rather than counted as zero, which would understate the holding; the screen says so when there is any.
8. **Shrinkage** (inventory-svc) — stock written off and found over a period, grouped by reason, staff member or store. Write-offs and finds are shown as separate columns on purpose: a store that wrote off 100 units and found 100 others is not a store that did nothing, and a net figure alone would say it was. Date range.
9. **Staff Exceptions** (order-svc) — loss prevention's view: discounts granted, sales voided and no-sale drawer opens over a period, grouped by staff member or store, most exceptions first. Each row carries the staff member's journalled sales so the counts read as a rate (`per 100`) rather than a ranking of who worked the most shifts; when nothing journalled a sale the rate shows `—` and the screen says why, because a zero there would read as "impeccably behaved". Exceptions recorded with no actor are shown as **Unattributed** rather than dropped. Date range.
10. **Tax Summary** (pricing-svc) — net / VAT / gross by rate band, store or month, reconciling to VAT return boxes 1 and 6. Exempt lines are marked, because the return counts their net in Box 6 and their VAT in no box at all. When Box 1 and total VAT disagree an exempt line is carrying VAT — the screen warns before the return is filed rather than dropping it silently. Date range (**required** here; pricing-svc rejects the call without one).
11. **Sales by Hour** (order-svc) — takings bucketed by hour of the trading day with a bar per hour, so a manager can staff to the actual peak rather than to a guess. Counted on the **browser's own clock**: the client sends its timezone, because the server stores UTC and a shop outside it would otherwise be told it is busiest at the wrong time of day. Channel chips compare the till against the website. Hours with no trade produce no row, and the screen says how many of the 24 are absent rather than letting a short table read as a quiet day. Date range.
12. **Sales by Staff** (order-svc) — what each cashier rang up, their average basket, and their discount rate as a share of the *undiscounted* ticket. Read from the POS transaction journal, so **in-store only** — a note on the screen says so, because a manager comparing it against Sales Revenue will otherwise be chasing the online orders that have no cashier to attribute. Entries naming nobody show as **Unattributed** rather than being dropped. Date range.
13. **Tender Mix** (payment-svc) — how the take split across cash, card, gift card and the rest, with each method's share of the net. Refunds are subtracted **within their own method**: a card sale refunded to store credit is not a zero-card day, and the split is what reconciles against a merchant statement. Failed tenders are counted in their own column and called out above the table, because a method whose declines climb against healthy volume is a terminal problem no sales report would show. Date range.
14. **Stock Turn** (inventory-svc) — cost of goods sold against the average value held to produce it, with turns and days-on-hand, slowest first. Two separate caveats appear when they apply and mean different things: that the movement ledger was archived past the window's start, so opening values are a floor; and that some quantity sold out of batches with no cost price, excluded from COGS rather than costed at zero. Turns show `—` rather than `0` where there was nothing to turn — a different finding. Date range (**required**; the endpoint rejects the call without one).
15. **Dead Stock** (inventory-svc) — stock on hand aged into the 0-30 / 31-60 / 61-90 / 91-180 / 180+ ladder, with the value sitting in each band and the total at risk in the header. A **Since** column names which date each age is measured from — "last sale", or "received" where the line has never sold — because 400 days since receipt and 400 days since a sale are not the same claim. No date bar: dead stock is a question about now, not about a period.

### 4.8 Customers

- List/search customers; **Add** (name, email, phone, etc.) and **Edit** (adds gender, DOB).
- **Customer detail**: loyalty points balance + tier, store-credit balance, and four quick actions — **Earn points**, **Redeem points**, **Issue credit**, **Redeem credit** — each opening an amount + reason mini-dialog. A scrollable **loyalty ledger** shows the last 20 movements with reason and +/- amount.
- **Addresses**: add/edit saved addresses tagged Home/Work/Billing/Shipping, with a "Default" indicator.
- **Export data** — a subject access / portability request (art.15/20) that reached the shop by phone or letter: the same document the storefront's *Download my data* produces, copied to the clipboard and shown. All of it or none of it — if order-svc cannot be reached nothing partial is produced.
- **Anonymize** (with confirmation) — a GDPR-style right-to-be-forgotten control that scrubs the customer's personal details and their saved addresses, and tells order-svc/notification-svc to redact what each holds about them (SJ-D43): a settled order is redacted at once, an order still being fulfilled once it finishes.

### 4.9 Sales tools (4 tabs)

| Tab | What the user does |
|---|---|
| **Gift Cards** | Look up a card by code; see status and transaction history; **Reload** or **Redeem**; **Issue** a new one (store, amount, currency) — the generated code is shown once in a copyable field. |
| **Layaways** | Look up an existing layaway, or start a new one: store, line items, initial deposit. Existing layaways show total/paid/balance/status with **Add deposit**, **Complete**, or **Cancel**. |
| **Special Orders** | List (customer name, status). **New**: store, customer name/phone, line items — for stock a store doesn't currently carry but will bring in for that customer. |
| **Receipts** | The **legal receipt register** for a store: the series it runs (code, fiscal year, prefix, next number), the audit — *Sequence intact* or *Sequence has gaps* with each missing range, first/last/issued/expected — answered by the server from the table, and every receipt in number order with its order ref and total, a voided sale shown as VOID and keeping its number. A manager sets what a series prints in front of its numbers (*Set prefix*, e.g. `GB-LDN-01`); a bad prefix is refused by the server and the dialog says why. The audit also shows the **hash chain** verdict — intact from a given number, or broken at the first document whose figures no longer match — and **Export** fetches the register as CSV (with both hashes on every row) to copy and save. Nothing on this tab can renumber, delete or edit a receipt. |

### 4.10 Staff

- List of staff assigned to stores: email, role badge, store. **Remove** (with confirmation).
- **Assign Staff**: enter an email (finds an existing account or provisions a new one), optional password, a role (Owner / Manager / Storekeeper / Cashier), and a store. If a new account was created, a one-time **"Staff account created"** dialog reveals a temporary password (masked by default, tap-to-reveal, one-tap copy with a "clear your clipboard" reminder).
- Also reachable from every Admin screen's app bar: **Change password** and **Sign out**.

**Key admin flows:**
- *New product to sellable:* Catalog → New Product → add a Variant (SKU/barcode/unit) → Set price → (optional) restrict "Sold at stores" → product image → now visible on POS/storefront.
- *Bulk catalog setup:* Catalog → Import → upload CSV → review parsed preview → map store names → choose destination store → import → review per-category error report.
- *Purchase-to-shelf:* Procurement → Create PO → add lines with cost/tax → Submit → (goods arrive) → Receive goods → stock levels update, visible immediately in Inventory.
- *Order lifecycle (either channel):* Orders → Confirm → Mark fulfilled / Collect payment (if COD) → optionally Return/Refund later.
- *Staffing a new store:* Stores → Add Store (+ Zones) → Staff → Assign Staff (role + store) → temp password handed to the new hire.

**Notable UX/product features:** barcode camera scanning reused in Products (variant barcode) and Inventory (receive stock); a consistent responsive wide-table/narrow-card-list pattern across every list screen; consistent "Delist"/"Deactivate"/"Remove" terminology per entity type; per-store **payment-method** and **Catalog-mode** toggles that ripple into POS and Storefront; a few sensitive actions (special-order creation, payment collection) are safe to retry without double-effect.

---

## 5. POS — cashier-facing terminal

**Persona:** in-store cashiers (and managers acting as cashiers). Built to work as a real till on a tablet or desktop, and to degrade sensibly on a phone.

**Entry point — Clock-in:** the whole terminal is gated behind clocking in — a cashier picks a store/terminal before any sale can start; a background heartbeat keeps the session alive while clocked in, and "Clock out" is one tap away (with a confirmation that in-progress sales are preserved). Once clocked in, the default landing is the Sale screen.

Navigation: **Sale · Tender · Cash · Pending** (a distinct amber accent branding sets the POS apart from the Admin Console's look). *Pending* carries a badge with the number of sales the server has not accepted yet, so an unsynced sale is visible from anywhere in the terminal.

| Screen | What the user does |
|---|---|
| **Sale** | On wide terminals: a persistent two-pane layout — product catalog on the left (search, in-stock filter, category chips, tap-to-add), the running sale on the right. On phones: the sale view only, with a **Browse** button opening the same catalog as a bottom sheet. A barcode field accepts a camera scan, a handheld scanner, or manual entry. Each line supports qty +/- and swipe-to-remove. A customer bar attaches a registered customer or records a walk-in's phone number — **required on every sale**: tendering is blocked until one or the other is present. Actions: **Hold** (park the sale, with a discard-confirmation if resuming over unsaved changes), **Resume** (pick from held sales), **No sale** (open the drawer without a transaction, logged for loss-prevention). A totals bar shows subtotal, an optional order-level discount, and net total, with a **Charge** button. |
| **Tender** | **Split/multi-tender payment**: add one or more payments (Cash, Card, UPI, Wallet, Gift Card, Store Credit) until the balance clears; cash/card entries offer quick preset amounts or "Exact," cash shows change due; gift-card/store-credit entries look up and cap against the actual balance. Added tenders can be removed before completing. On completion: a "Sale complete" confirmation with order number and change due, plus **Reprint** and **Email** receipt actions, then "New sale." (The Email action records the receipt + address against the order and reports success, but no email is actually dispatched today — there's no receipt consumer behind it.) Completing a sale hands it over: the order is fulfilled when the last tender lands, so its stock is deducted without anyone opening it afterwards. Until SJ-D40 every till sale waited at *Confirmed* for a manager to click *Mark fulfilled*, and its stock never moved if nobody did. The receipt carries the **legal receipt number** (e.g. `2026-000042`): the number is issued a few seconds after the last tender, so the till asks order-svc to hold the request while the number is issued (`?wait=12`, one round trip in the common case) and retries briefly after, rather than polling sixteen times and giving up. If it has not arrived, the receipt says so instead of printing a number, and *Reprint* fetches it again. An offline sale's receipt says its number is issued when the sale reaches the server — and once it does, the **Offline sales** screen lists the synced sale under the same reference the paper receipt carries, with the receipt number the server issued on replay (looked up when the screen is opened, remembered once found, *Check again* if it is not issued yet). (The receipt used to print "Receipt #" over the first eight characters of the order's id — an order reference wearing a receipt number's label; it is now labelled *Order ref*.) |
| **Tender** *(Catalog-mode store)* | Skips payment entirely when the clocked-in store has prices hidden: lists the items and a single **Place order** button — no prices, no tender, just an order record for later fulfilment/pricing. **Open (SJ-D41):** that order stays `PENDING`, and the pending-order sweeper cancels every `PENDING` order past its time-to-live with no exception for catalog mode, so an order placed this way is cancelled while it waits to be priced. |
| **Cash / Till** | **Open till**: enter a starting cash float. Once open: **Cash drop**, **Pay in**, **Pay out** (each a reason + amount mini-dialog), and **Close till (Z-report)** — shows expected cash, prompts for the counted amount, and produces a "till closed" confirmation. |
| **Pending** | Sales taken while the server was unreachable, held on the till until it accepts them. Each row shows the sale reference printed on the customer's receipt, the total, when it was taken, and how many attempts it has had. Waiting sales retry on their own; a sale the server has *refused* is parked with the reason and offers **Try again** and **Discard** (confirmed, because the customer has already paid). **Sync now** forces a replay. |

**Flows:**
- *Start of shift:* Clock in (pick store) → Sale screen unlocked.
- *Ring up a sale:* scan/search items → attach customer or walk-in phone (required) → Hold or Charge → Tender (splitting across methods as needed) → receipt → New sale.
- *Age-restricted items:* scanning or picking an item that is age-restricted in the store's country stops before it reaches the sale and asks the cashier to check — the item, its category, the minimum age, and whether that age is the legal minimum or the store's own stricter policy. **Refuse sale** keeps it out; **Checked — 18+** lets it in. The dialog cannot be dismissed by tapping away. One check covers the rest of the sale at that age, and is asked again for an item with a higher age, when the basket is cleared, or when a parked sale is resumed. **If the till can't find out** — the store has no country, the country has no rule for that category, or product-svc can't be reached — the item is refused with the reason, not waved through: a till that treats *couldn't tell* as *not restricted* sells alcohol to a child while every screen says it asked. Every decision is recorded (`POST /pos/age-checks`): **Refuse sale** asks why — under age, no ID, ID not accepted, buying for someone under age, other — and **Record refusal** writes it; **Checked — 18+** may note what was shown (passport, driving licence, PASS card, …). The decision stands whether or not the write succeeds — a network fault never turns a refusal into a sale — and the cashier is told to inform a manager if it could not be written. Managers read the register under **Admin → Age checks**: every check by store and period, the refusals and their reasons, the counts a licensing officer asks for first.
- *Recalls:* the till keeps the list of open recalls, refreshed every five minutes, and checks every scanned or picked item against it before anything else. **Every pack recalled:** *Do not sell this item*, with the reference, the hazard and the customer notice, and one button — *Remove from sale*; there is no override. **Only some lots or dates recalled:** *Check the pack before selling*, listing exactly what to look for (*lot L-2291, best before 1 Oct 2026 to 31 Oct 2026*); *Affected — remove* is the prominent answer, *Not affected — sell* lets it in. When the list can't be refreshed for half an hour the till keeps selling against the list it has and shows *Recalls haven't refreshed for a while. Check items against the recall notices.* — blocking every sale because inventory-svc is down would close the shop.
- *Weighed items:* an item sold by weight, volume or length asks for the reading before it reaches the sale — the item, its price per unit, and *Enter the weight the approved scale shows. Do not estimate it.* The line price updates as the reading is typed (to the gram; a decimal comma is accepted). Where the product declares packaging weight the dialog says the scale deducts it, and the till subtracts nothing itself. In the basket a weighed line shows its reading (e.g. *0.375 kg*) and counts as one item; tapping it reads the scale again, and it has no ± buttons. The receipt prints *0.375 kg × GBP 12.00/kg*. If the till can't tell how an item is sold it is kept out, not rung up as a single unit. Until this existed a line's quantity was a whole number and no loose item could be sold by weight at all. A parked weighed line resumes with its weight, though — as for every parked line — without its name or unit. Before it asks for a reading the till checks the store's instrument register: only a scale *certified for trade* is offered (one is picked silently; two or more ask *Which scale?*), and with none — or when the register cannot be read — the sale by weight is refused with the reason (Weights and Measures Act 1985 s.11). The line records which scale it was weighed on. A barcode printed by a certified labelling scale is read exactly by that scale's scheme — the item code, and the price or weight it encodes, check digit verified — and rings the item up already weighed on that scale, without the dialog; a label the scheme cannot read is looked up as an ordinary barcode. The counter scale's own reading is still keyed: a serial feed from the instrument needs certified hardware and an approval process, not an endpoint.
- *Interrupted sale:* Hold → serve another customer → Resume (discarding or completing the interrupted cart).
- *Network drops mid-shift:* the sale completes at the till — the customer pays, the receipt prints with an offline reference — and the writes it owes the server are queued. The till keeps selling; the queue drains by itself when the line comes back. Sales that are still waiting survive a sign-out and an app restart, and clocking out warns how many are outstanding.
- *End of shift:* Close till → count cash → Z-report → Clock out.

**Notable UX/product features:** camera + hardware-scanner + manual-entry barcode input all funnel through the same lookup; "No sale" and cash drop/pay-in/pay-out give the till an auditable cash-management trail; receipts are printable HTML opened in a browser tab (a web-first, browser-print workflow rather than direct receipt-printer integration); Catalog-mode stores turn the POS into an order-taking terminal with no payment step at all; an offline store-and-forward queue means a dropped network stops the *syncing*, not the *selling*.

**Offline limits, stated plainly.** The queue covers completing a sale, not starting one: scanning still resolves the barcode and price against the server, so an offline till can finish a sale whose lines are already in the cart but cannot ring up a new item. A cached catalog is the separate, larger piece of work. Gift-card and store-credit tenders can only be *staged* while online (the balance lookup needs the server), so they never enter the queue from a cold-offline till — but a sale interrupted after they were staged carries them, and a balance that has since gone is surfaced as a parked sale rather than silently swallowed.

---

## 6. Storefront — public, customer-facing shop

**Persona:** end customers — guest or signed in — browsing and buying from a business's online shop. Reachable without login; has its own lightweight customer sign-in, separate from Admin/POS credentials.

**Home — Shop:** a store switcher at the top (only shown when the business has 2+ stores), an auto-rotating offers carousel driven by the business's live promotions (falls back to generic content when none are live), a search bar, category/"in stock" filter chips, and a responsive product grid/list. A small self-promotional card for the platform itself is mixed into the product grid at a random position among the real products.

Navigation: **Shop · Cart** (with a live item-count badge). A sticky cart bar (subtotal + item count + "View cart") floats above the content on every screen except the cart itself, once something's in the basket.

| Screen | What the user does |
|---|---|
| **Shop** | See above. First-time visitors are shown a skippable "tell us about yourself" preference prompt shortly after arriving. |
| **Product Detail** | Image, name, description, and an "Options" list of purchasable variants (SKU, unit, barcode), each with a stock badge and an **Add** button. In Catalog-mode stores, prices are hidden entirely — just stock + Add. Under each option, what the shopper is told about allergens: *Contains: … May contain: …* for a declared product; *Contains none of the 14 regulated allergens* only when that was positively declared; nothing for a product that is not food; and *Allergen information is not available for this product. Please ask in store before buying.* for anything undeclared or unreadable — including while the app is retrying a failed load, so a failure never reads as nothing to declare. |
| **Cart & Checkout** | Line items with qty +/- (and totals, if prices are shown). Browsing and carting need no account, but tapping checkout as a guest opens the sign-in/registration dialog and won't proceed without it — **every order is placed by a signed-in customer** (the gateway only accepts order placement from a verified customer token). Fulfilment choice: **Collect from store** or **Deliver to home** — delivery needs a full address (line 1/2, city, postal code, recipient name & phone); pickup just needs a contact phone. Payment options build dynamically from what the store accepts and whether prices are shown (Card/UPI/Wallet only appear when priced; Cash always reads as "cash on delivery/at pickup"; a store with no applicable method still gets a generic "pay on/at…" fallback so checkout never dead-ends). A **Review your order** step precedes placing it. If a pending order already exists for the same cart/customer, the app offers to view/update it instead of silently duplicating. Ends in a "Payment successful"/"Order placed" confirmation. |
| **My Orders** | Signed-in customers see a real, cross-device order history; guests just see a banner nudging them to sign in (the screen has a device-local list for unauthenticated orders, but since checkout requires sign-in it stays empty in practice). Each order shows fulfilment type, date, status, and total (or "Price on delivery/in store" in Catalog mode). |

**Account & engagement surfaces (dialogs/sheets, not separate screens):**
- **Sign in / Create account** — email + password (+ phone required to register); reusable from the account menu or at checkout.
- **Account menu** (signed in): My orders, My preferences, **Privacy & marketing**, Send feedback, Sign out, **Delete my account**. (Guest): Sign in, Send feedback.
- **Privacy & marketing** (`/store/privacy`) — the shopper's preference centre and their data. Four marketing switches (email, text, phone, post), all off until the shopper turns one on; each change is saved with the wording shown beside them, because UK GDPR art.7(1) makes the shop prove what was agreed to (PECR reg.22). Below it, **Download my data** (art.20): everything this shop holds — profile, addresses, loyalty and store credit with their histories, consent trail, every order — as one JSON document, copied to the clipboard and shown. It fails loudly rather than partially: if order-svc cannot be reached the shopper is told to try again and nothing is handed over. Signed-out visitors are asked to sign in.
- **Delete my account** (SJ-D43) — self-service login deletion, separate from a shop erasing its record of the customer (§4.8's Anonymize). Re-asks the password (a session left open on a shared device isn't enough), then deletes the login and signs the device out. What individual shops hold — orders, loyalty, their own customer profile — is untouched; each is a separate request to that shop.
- **Preferences** — "Shopping for" (Myself/Family/Business, multi-select) and a notification preference (Order updates / Promotions / Both / None); shown once automatically right after first sign-in/registration if not yet answered, or reachable anytime.
- **Feedback** — category (Bug / Feature request / Compliment / Other), free-text description, optional 1–5 star rating; available any time.
- **Post-order survey** — a 1–5 emoji experience rating, a 1–10 "would you recommend us" slider, and an optional comment, meant to appear after checkout completes.
- **Store-unavailable notice** — if the business has been deactivated by the platform, the whole storefront shows a "currently unavailable" message instead of the shop.

**Flows:**
- *Guest browsing → first purchase:* land on Shop → (skippable preference prompt) → browse/search/filter → Product Detail → Add to cart → Cart → **sign in / create account (required here)** → choose Pickup/Delivery → choose payment → Review → Place order/Pay → confirmation.
- *Returning customer:* Sign in → Preferences (first time) → shop → My Orders shows the full synced history.
- *Post-purchase engagement:* Order placed → (later) post-order survey; any time → Send feedback from the account menu.

**Notable UX/product features:** multi-store switching within one business's storefront; a native ad slot mixed into organic product listings; anonymous browsing with a low-friction sign-in gate placed at checkout (account creation is required to buy, but nowhere earlier); a duplicate-order guard; Catalog mode strips prices/payment consistently across product list, detail, cart, and order history; four lightweight, skippable customer-research surveys (preferences, feedback, post-order) built directly into the shopping experience.

---

## 7. Cross-cutting UX / product features

- **"Catalog mode"** (a per-store "show prices" toggle, set from Admin → Stores → Edit) turns off pricing everywhere that store's customers/staff touch it — storefront listings/detail/cart/orders show stock only ("Add," no price), the POS Tender screen skips payment collection entirely and becomes a "Place order" ticket, and receipts/orders show "Price on delivery"/"Price in store" instead of a total. This is a first-class, deliberately designed alternate business mode (e.g. for wholesale/quote-based or regulated-pricing retailers) — see [README §16](../README.md#16-catalog-mode--selling-without-showing-a-price).
- **Barcode scanning:** one shared full-screen camera scanner (with a flash/torch toggle) is reused in three places — Admin Products (tag a variant's barcode), Admin Inventory (resolve a variant when receiving stock), and POS Sale (ring up items) — always with manual text entry as a fallback, since desktop browsers have no camera-scanning path.
- **Dark mode:** full light/dark theming that follows the OS setting; a warm ivory/charcoal/amber/forest-green palette with dedicated, colour-blind-safe success/warning colors (always paired with text or an icon, never colour alone).
- **Localization:** 8 languages ship at the framework level — English (UK, default), Polish, Romanian, Punjabi, Urdu, Bengali, Gujarati, Arabic — chosen to match the largest non-English-speaking communities in the UK. Urdu and Arabic auto-mirror the whole layout right-to-left. In practice, only the Login/Register screen's strings are fully translated today; the rest of the interface is still English, with translation coverage layered on top of already-complete RTL/date/number framework support.
- **Multi-currency:** every money-creating flow (onboarding, stores, price lists, promotions, gift cards, purchase orders/suppliers, layaways) offers the same 5 currencies (INR/USD/GBP/SGD/AED) tied to the 5 supported countries.
- **Receipts:** generated as printable HTML opened in a new browser tab that auto-invokes the browser's print dialog (a web-first design); the POS "email receipt" action records the audit row **and** delivers a plain-text receipt via notification-svc (SMTP when `shelfj.notification.channel=email`, otherwise the APP log channel).
- **Auditable "soft" cash/inventory actions:** POS "No sale" and cash drop/pay-in/pay-out are explicit, logged, non-sale operations distinct from a Charge — a loss-prevention/reconciliation feature.
- **Reliability touches:** temp passwords for newly-provisioned staff accounts are shown exactly once (masked by default, explicit reveal + copy, with a "clear your clipboard" reminder); a few sensitive create actions (special orders, POS payment collection) are safe to retry without double effect; tenant deactivation is enforced end-to-end (the storefront shows a "closed" notice rather than a raw error).
- **Reports:** every report with rows offers client-side CSV export; period reports carry a date bar and grouped reports carry grouping chips. One format trap is handled in the client rather than left to the caller: the date pickers speak `yyyy-MM-dd`, but the inventory and pricing report endpoints parse `from`/`to` with `Instant.parse` and reject a bare date, so the client widens a day to `T00:00:00Z`/`T23:59:59Z` — `to` covering the *end* of its day, or a report run "to today" would silently exclude today.

### 7.1 Material 3 conventions

- **Color:** `AppTheme.light`/`.dark` (`core/theme.dart`) build the `ColorScheme` with `ColorScheme.fromSeed(seedColor: charcoal, ...)`, overriding only the roles the brand has an opinion on (primary/secondary + containers, surface, outline). Everything else — tertiary, error, the 5 surface-container tones, inverse roles — is algorithm-derived from that seed so it stays contrast-correct and internally consistent. Don't hand-add a one-off `Color(0x...)` for a new UI element; derive it from `Theme.of(context).colorScheme` (or extend `StatusColors`/`ChannelAccent` in `theme.dart` for a new semantic/channel role).
- **Channel accents (POS):** the POS terminal's amber accent is a `ChannelAccent` `ThemeExtension` applied via `AppTheme.applyPosAccent()`, scoped to the POS subtree by `PosShell`'s `Theme(...)` wrapper — not a raw `Color` constant threaded through widgets. Read it with `context.channelAccent.{color,onColor}` so the background and its contrasting foreground always travel together.
- **Navigation:** `AdaptiveNavShell`'s `compactStyle` picks the phone-width pattern — `CompactNavStyle.drawer` (default, for 6+ destinations) or `CompactNavStyle.bottomBar` (3–5 destinations). Don't add a 6th+ destination to a shell that uses `bottomBar` without reconsidering the pattern.
- **Icons:** the nav-destination convention is `_outlined` for unselected, filled (no suffix) for selected (see any `AdaptiveNavDestination`) — follow that pairing for new selectable icons. Elsewhere, prefer `_outlined` as the default icon style; the existing mix of filled/outlined glyphs on non-selectable icons predates this note and hasn't been swept, so match the surrounding screen rather than the nearest icon.
- **Banners:** use `MaterialBanner` (see `_ExpiringBanner` in `inventory_screen.dart` or `_SignInBanner` in `storefront/orders_screen.dart`) for a persistent, dismissible, 1–2-action message — not a hand-rolled `Container`/`Material` block.
- **Deferred (needs a product/data decision, not just a widget swap):** a Time picker for store business hours — `businessHours` is currently a raw `String` with no structured per-day open/close shape, so there's no data model for a picker to write into yet. A `RangeSlider` price filter on the storefront catalog — product-svc/pricing-svc don't currently accept a min/max price query param, so the control would have nothing to filter against.

---

## 8. Full route reference

| Route | Screen |
|---|---|
| `/login` | Login / Register |
| `/platform/login` | Platform console sign-in |
| `/onboarding` | Business setup wizard (2 steps) |
| `/platform/overview` | Platform Overview (dashboard) |
| `/platform/tenants` | Tenants list + onboarding dialog |
| `/admin/dashboard` | Dashboard |
| `/admin/catalog` | Catalog (Products / Categories / Import tabs) |
| `/admin/inventory` | Inventory (Levels / Batches tabs) + Receive Stock |
| `/admin/food-safety` | Food safety: Today / Diary, plus Setup / Reviews for managers |
| `/admin/recalls` | Recalls: list, detail with store actions; open / close / cancel for managers |
| `/admin/stores` | Stores + Zones |
| `/admin/orders` | Orders (all channels) + Return/Refund + Collect Payment |
| `/admin/procurement` | Procurement (Purchase Orders / Suppliers tabs) |
| `/admin/pricing` | Pricing (Price Lists / Promotions / VAT Rates / VAT Return tabs) |
| `/admin/reports` | Reports (On-Hand / Sales / Sales by Day / Supply-Demand / Movements / Low Stock / Valuation / Shrinkage / Staff Exceptions / Tax Summary / Sales by Hour / Sales by Staff / Tender Mix / Stock Turn / Dead Stock) |
| `/admin/customers` | Customers + loyalty/credit + addresses |
| `/admin/sales` | Sales tools (Gift Cards / Layaways / Special Orders / Receipts tabs) |
| `/admin/staff` | Staff |
| `/pos/cart` | POS Sale (or Clock-in gate if no session) |
| `/pos/tender` | POS Tender (or "Place order" view in Catalog mode) |
| `/pos/cash` | POS Cash / Till |
| `/store/products` | Shop (product list) |
| `/store/products/:id` | Product Detail |
| `/store/cart` | Cart & Checkout |
| `/store/orders` | My Orders |

(`/platform`, `/admin`, `/pos`, and `/store` redirect to their area's default screen.)

---

*Companion documents: [README.md](../README.md) (product tour) · [docs/API-GUIDE.md](API-GUIDE.md) (API surface by business capability) · [docs/ARCHITECTURE.md](ARCHITECTURE.md) (engineering reference, frontend internals in §13) · [PRD.md](../PRD.md).*
