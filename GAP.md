# Shelf-J — Industry Standard Gap Analysis

> **Sources:** Oracle Inventory User's Guide (R12.1) · Oracle Retail Point-of-Service Release Notes (R1.3.0)
>
> **Coverage key:** ✅ Full · ⚠️ Partial · ❌ Missing

---

## Oracle Inventory User's Guide (R12.1)

### Ch. 1 — Setting Up

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Profile options / setup checklist | ⚠️ Partial | External config service exists; no admin setup UI or profile option management |
| Inventory org parameters | ❌ Missing | Maps to tenant-level config; Shelf-J has tenants but no inventory-specific profile knobs |

---

### Ch. 2 — Inventory Structure

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Organization (= tenant business) | ✅ Full | `tenants` table in tenant-svc |
| Sub-inventory (= store/warehouse) | ✅ Full | `stores` table (type STORE/WAREHOUSE) |
| Stock locator (= zone/aisle) | ⚠️ Partial | `zones` table exists; no `locator_type`, no locator picking rules |
| Subinventory GL account mapping | ❌ Missing | No chart-of-accounts integration |
| Inter-organization shipping network | ❌ Missing | No transfer routes between stores |
| Shipping methods | ❌ Missing | — |
| Intercompany relations / economic zones | ❌ Missing | Single-tenant-only flows; no multi-entity accounting |
| Costing information (valuation accounts) | ❌ Missing | `cost_price` field only; no standard/average costing |
| Revision / Lot / Serial / LPN parameters | ❌ Missing | Batch + expiry exist; no org-level lot/serial parameter controls |

---

### Ch. 3 — Unit of Measure

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| UOM classes (Weight, Volume, Each, …) | ❌ Missing | No UOM model anywhere |
| UOM definitions | ❌ Missing | Quantities stored as plain numbers with no unit attached |
| UOM conversions (item-level + standard) | ❌ Missing | Critical for purchase-order vs. sales-unit differences |
| Lot-specific UOM conversions | ❌ Missing | — |

> **Priority: HIGH** — A product sold by each but purchased by the case needs UOM conversion throughout inventory, ordering, and pricing.

---

### Ch. 4 & 5 — Item Setup / Control / Attributes

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Product master (= Item master) | ✅ Full | `products` + `product_variants` in product-svc |
| Product categories | ✅ Full | `categories` + `brands` |
| Item status codes (Active, Obsolete, …) | ⚠️ Partial | `status` enum on product; no forward-pending-status workflow |
| Item templates | ❌ Missing | No template system for bulk attribute assignment |
| Item revisions | ❌ Missing | No `product_revisions` table |
| Item relationships (substitute / complementary) | ❌ Missing | — |
| Customer items / cross-references | ❌ Missing | No supplier/customer part-number cross-ref |
| Manufacturer part numbers | ❌ Missing | — |
| Item catalog groups / descriptive elements | ❌ Missing | No structured specification metadata |
| Item attribute groups (18 groups: Costing, Lead Times, Purchasing, Receiving, WIP, Service, Web, …) | ❌ Missing | product-svc has `color`, `size`, `weight_grams`, `sku` only |
| Container types / cartonization | ❌ Missing | — |
| Picking rules | ❌ Missing | — |
| Category sets + flexfields | ❌ Missing | Single flat category tree only |
| Open Item Interface (bulk import) | ❌ Missing | No import endpoint or batch job |

---

### Ch. 7 — Lot Control

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Lot numbers | ⚠️ Partial | `batch_ref` + `expiry_date` on `inventory_batches` |
| Grade control | ❌ Missing | — |
| Lot action codes (split / merge / transfer) | ❌ Missing | — |
| Lot genealogy (child ← parent) | ❌ Missing | No parentage chain on batches |
| Lot expiry auto-reporting | ❌ Missing | No scheduled Expired Lots report or alert |
| Lot-specific UOM conversion | ❌ Missing | — |

---

### Ch. 8 — Serial Control

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Serial number tracking | ❌ Missing | Entire chapter absent in Shelf-J |
| Serial number generation | ❌ Missing | — |
| Serial genealogy | ❌ Missing | — |
| Serialized cycle counting | ❌ Missing | — |

> **Priority: HIGH** — Required for high-value electronics, appliances, and warranty tracking.

---

### Ch. 9 — Material Status Control

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Material status (Quarantine, Inspection, Active, Damaged, …) | ❌ Missing | `inventory_batches` has no status field; all stock is implicitly available |

> **Priority: MEDIUM** — Needed for receiving inspection, damaged goods, and recall holds.

---

### Ch. 10 — Transaction Setup

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Configurable transaction source types | ❌ Missing | `movement_type` enum is hardcoded (RECEIVE/SELL/ADJUST/RESERVE_RELEASE/RETURN) |
| Transaction reasons | ❌ Missing | No reason codes |
| Account aliases | ❌ Missing | — |
| Consumption transaction rules | ❌ Missing | — |

---

### Ch. 11 — Transactions

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Receiving transactions | ⚠️ Partial | `POST /admin/inventory/batches` creates a batch (RECEIVE movement); no PO reference |
| Sub-inventory transfers (zone-to-zone) | ❌ Missing | No zone-to-zone or store-to-store transfer API |
| Miscellaneous issue / receipt | ⚠️ Partial | ADJUST movement type exists but no reason codes or GL account |
| Inter-organization transfers (direct) | ❌ Missing | No cross-store transfer |
| Inter-organization transfers (intransit) | ❌ Missing | — |
| Consignment / VMI material | ❌ Missing | — |
| Shortage alerts & notifications | ❌ Missing | `reorder_thresholds` stores data; no alert/notification dispatch |
| Movement statistics | ❌ Missing | No aggregated demand-history tables |
| Transaction audit / history drill-down | ⚠️ Partial | `stock_movements` append-only log; no GL drill-down |
| Purge transaction history | ❌ Missing | — |

---

### Ch. 12 — On-hand and Availability

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| On-hand quantity by store / zone | ✅ Full | `GET /admin/inventory/levels?store=&sku=` |
| Multi-org quantity report | ❌ Missing | No cross-store aggregate view |
| Item supply / demand netting | ❌ Missing | On-hand only; no supply (PO, transfer) or demand (open orders) netting |
| Item reservations (create / view) | ✅ Full | `POST /admin/inventory/reserve` + status check |
| Reservation interface (batch) | ❌ Missing | — |
| Reservation expiry sweep | ✅ Full | TTL sweeper (900 s default) releases abandoned-cart reservations |

---

### Ch. 13 — Move Orders

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Move order requisitions | ❌ Missing | No move order entity |
| Replenishment move orders | ❌ Missing | — |
| Pick slip grouping rules | ❌ Missing | — |
| Material pick wave process | ❌ Missing | — |
| Express pick release | ❌ Missing | — |

> **Priority: HIGH** — Essential once order-svc and purchase-svc are built; fulfillment needs pick waves.

---

### Ch. 14 — Planning and Replenishment

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Reorder thresholds (manual floor) | ✅ Full | `reorder_thresholds` table with `min_qty` / `reorder_qty` per store/SKU |
| Min-Max planning engine | ❌ Missing | No engine runs the min-max formula; no auto-requisition generation |
| Reorder Point planning (EOQ + safety stock) | ❌ Missing | No EOQ formula, no demand smoothing, no safety stock calculation |
| Kanban replenishment — Supplier type | ❌ Missing | — |
| Kanban replenishment — Inter-Org type | ❌ Missing | — |
| Kanban replenishment — Intra-Org type | ❌ Missing | — |
| Kanban replenishment — Production type | ❌ Missing | — |
| Demand history summarization (buckets) | ❌ Missing | `stock_movements` has raw events; no aggregated demand buckets |
| Forecast rules (Focus / Statistical / Exponential Smoothing) | ❌ Missing | — |
| Safety stock (MAD / user-defined %) | ❌ Missing | — |
| Replenishment counting (PAR levels) | ❌ Missing | — |
| Order modifiers (min / max / fixed-lot multiplier) | ❌ Missing | — |
| Auto purchase-requisition generation | ❌ Missing | purchase-svc not yet built |

> **This is the single largest gap.** Shelf-J stores the threshold data but has zero planning intelligence or automation.

---

### Ch. 15 — Cost Control & Accounting

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Costing methods (Standard / Average / FIFO / LIFO) | ❌ Missing | `cost_price` field only; no valuation method |
| Accounting periods (open / close) | ❌ Missing | — |
| Period close cycle | ❌ Missing | — |
| Material account distributions | ❌ Missing | — |

---

### Ch. 16 — ABC Analysis

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| ABC compile (by value, velocity, …) | ❌ Missing | — |
| ABC classes (A / B / C) | ❌ Missing | — |
| ABC assignment groups | ❌ Missing | — |
| Use in cycle count frequency assignment | ❌ Missing | — |

---

### Ch. 17 — Cycle Counting

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Cycle count definition | ❌ Missing | Entire feature absent |
| Automatic schedule generation | ❌ Missing | — |
| Count entry & approvals | ❌ Missing | — |
| Adjustment with tolerance bands | ❌ Missing | — |
| Serialized cycle counting | ❌ Missing | — |

---

### Ch. 18 — Physical Inventory

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| Snapshot of on-hand quantities | ❌ Missing | — |
| Physical inventory tags | ❌ Missing | — |
| Variance approval & adjustment | ❌ Missing | — |
| Reconciliation | ❌ Missing | — |

---

### Ch. 19 — Intercompany Invoicing

| Oracle Concept | Shelf-J State | Notes |
|---|---|---|
| AR / AP invoicing on inter-org transfers | ❌ Missing | No accounting integration |

---

## Oracle Retail Point-of-Service (R1.3.0)

| Oracle POS Feature | Shelf-J State | Notes |
|---|---|---|
| POS transaction engine | ✅ Full | order-svc: place/confirm/fulfil/cancel, post-void, returns, layaway, gift cards |
| Return / refund transactions | ✅ Full | `POST /orders/{id}/returns` with per-line refund calculation |
| Post-void (cancel completed transaction) | ✅ Full | `POST /orders/{id}/void` (POS-only, append-only pos_void_log) |
| Layaway (deposit + deferred pickup) | ✅ Full | create, add-deposit, complete, cancel; append-only deposit ledger |
| Special orders (customer order at store) | ❌ Missing | — |
| Gift cards (issue / reload / redeem) | ✅ Full | issue, reload, redeem (balance-guard), append-only transaction ledger |
| Tax-exempt transactions | ❌ Missing | No tax model at all |
| Tax rate captured in POSLog | ❌ Missing | — |
| Price overrides / price changes | ❌ Missing | `pricing-svc` planned; not built |
| E-check tender | ❌ Missing | — |
| Multi-currency / non-base currency tender | ❌ Missing | `currency` column exists on tenant; no multi-currency arithmetic |
| Instant credit enrollment at POS | ❌ Missing | — |
| Store Inventory Management (SIM) integration | ❌ Missing | SIM ↔ POS sync not modeled |
| VISA PABP (payment security compliance) | ❌ Missing | No payment processing built yet |
| POSLog / transaction journal | ❌ Missing | — |
| Receipt / e-journal printing | ❌ Missing | — |
| Timeout / session management | ⚠️ Partial | IAM has JWT refresh; no POS session idle timeout |
| Five-digit store ID support | ✅ Full | `stores` uses UUID; no field-length constraint issue |

---

## Prioritized Backlog

| # | Gap | Service | Oracle Reference |
|---|---|---|---|
| ~~1~~ | ~~**Min-Max planning engine** — automated replenishment suggestions + auto-requisition~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~2~~ | ~~**Unit of Measure model** — UOM classes, conversions, lot-UOM~~ ✅ | ~~product-svc~~ | ~~Ch. 3~~ |
| ~~3~~ | ~~**Serial number control** — generate, assign, genealogy~~ ✅ | ~~inventory-svc~~ | ~~Ch. 8~~ |
| ~~4~~ | ~~**Material status** — Quarantine / Inspection / Damaged / Recall on batches~~ ✅ | ~~inventory-svc~~ | ~~Ch. 9~~ |
| ~~5~~ | ~~**Move Orders / Pick Wave** — replenishment move orders, express pick release~~ ✅ | ~~inventory-svc~~ | ~~Ch. 13~~ |
| ~~6~~ | ~~**Transfer Orders** — inter-store stock transfers (direct + intransit)~~ ✅ | ~~inventory-svc~~ | ~~Ch. 11~~ |
| ~~7~~ | ~~**Demand history aggregation** — periodic buckets from stock_movements~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~8~~ | ~~**Safety stock calculation** (MAD formula + user-defined %)~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~9~~ | ~~**ABC analysis** — compile + assign A/B/C classes for cycle count frequency~~ ✅ | ~~inventory-svc~~ | ~~Ch. 16~~ |
| ~~10~~ | ~~**Cycle counting** — schedule, count entry, tolerance approval, adjustment~~ ✅ | ~~inventory-svc~~ | ~~Ch. 17~~ |
| ~~11~~ | ~~**Lot genealogy** — parent/child chain on batches~~ ✅ | ~~inventory-svc~~ | ~~Ch. 7~~ |
| ~~12~~ | ~~**Item revisions** — append-only versioning per variant with supersession~~ ✅ | ~~product-svc~~ | ~~Ch. 5~~ |
| ~~13~~ | ~~**Item templates** — named default-attribute sets applied to variants in one call~~ ✅ | ~~product-svc~~ | ~~Ch. 4~~ |
| ~~14~~ | ~~**POS transaction engine** — return, post-void, layaway, gift card~~ ✅ | ~~order-svc~~ | ~~POS RN~~ |
| ~~15~~ | ~~**Tax model** — UK VAT rates (T1/T5/T0/TX), price lists, promotions, POSLog, MTD VAT return~~ ✅ | ~~pricing-svc~~ | ~~POS RN~~ |
| ~~16~~ | ~~**Physical inventory reconciliation** — snapshot, tag counts, adjustment movements on complete~~ ✅ | ~~inventory-svc~~ | ~~Ch. 18~~ |
| ~~17~~ | ~~**Costing methods** (FIFO / average) + accounting period close~~ ✅ | ~~inventory-svc~~ | ~~Ch. 15~~ |
| ~~18~~ | ~~**Kanban replenishment** (all 4 types: Supplier / Inter-Org / Intra-Org / Production)~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~19~~ | ~~**Reorder Point planning with EOQ**~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~20~~ | ~~**Intercompany invoicing** — AR/AP on inter-org transfers, FRS 102 nominal ledger, BACS 30-day terms, Group VAT disregard~~ ✅ | ~~purchase-svc~~ | ~~Ch. 19~~ |

---

## Backlog 2 — Verified against actual code (items 21–55)

> Items 1–20 above were derived from the Oracle doc. Items 21–55 were found by reading the real service code and SQL migrations — these gaps exist regardless of what the doc says.
>
> **Build order:** Inventory completeness → Product richness → POS → Reporting → Blockers (payment-svc deferred until post-demo).

### Tier 1 — Inventory control completeness

| # | Gap | Service | Notes |
|---|---|---|---|
| 21 | **Transaction reason codes** — codified enum table + `reason_code` column on `stock_movements` | inventory-svc | `adjust` accepts free-text `reason` only; no controlled vocabulary |
| 22 | **Configurable transaction source types** — `transaction_source_types` table replacing hardcoded TEXT comment | inventory-svc | `movement_type` is a hardcoded SQL comment, not a managed reference table |
| 23 | **Lot action codes** — split / merge / transfer endpoints + corresponding movements | inventory-svc | No `lot_split` or `lot_merge` anywhere in Java or SQL |
| 24 | **Lot expiry auto-reporting** — scheduled job that alerts on batches past `expiry_date` | inventory-svc | No scheduler or notification dispatch for expired lots |
| 25 | **Lot grade control** — `grade` column on `inventory_batches` + grade-based picking | inventory-svc | No grade field in any migration |
| 26 | **Lot-specific UOM conversions** — per-lot conversion override table | inventory-svc | UOM item conversions exist in product-svc; no lot-level override |
| 27 | **PAR levels / replenishment counting** — `par_level_configs` table + counting endpoint | inventory-svc | Not in any migration or endpoint |
| 28 | **Order modifiers** — min/max order qty + fixed-lot multiplier on ROP plans, kanban cards, thresholds | inventory-svc | `rop_plans` and `kanban_cards` tables have no modifier columns |
| 29 | **Reservation batch interface** — bulk `POST /inventory/reservations/batch` endpoint | inventory-svc | Only single-reservation POST exists |
| 30 | **Purge transaction history** — admin endpoint + optional scheduled job | inventory-svc | No purge path; `stock_movements` grows forever |
| 31 | **GL account mapping** — subinventory/zone → nominal code mapping table | inventory-svc | No chart-of-accounts link on zones or batches |

### Tier 2 — Product catalogue richness

| # | Gap | Service | Notes |
|---|---|---|---|
| 32 | **Item relationships** — substitute / complementary links between variants | product-svc | No `item_relationships` table or endpoint |
| 33 | **Supplier / customer cross-references** — supplier part-number cross-ref table | product-svc | No cross-ref table in any migration |
| 34 | **Manufacturer part numbers** — `manufacturer_pn` field on `product_variants` | product-svc | Field absent from schema |
| 35 | **Item catalog groups / descriptive elements** — structured spec metadata beyond `attributes JSONB` | product-svc | Variants use an untyped JSONB bag only |
| 36 | **18 Oracle attribute groups** — typed model for Lead Times, Purchasing, Receiving, WIP, Web, etc. | product-svc | No attribute group model; untyped JSONB only |
| 37 | **Container types / cartonization** — container type reference table + variant link | product-svc | Not present |
| 38 | **Picking rules** — configurable pick-sequence rules (FEFO, FIFO, zone priority) | product-svc / inventory-svc | FIFO index exists; no rule engine |
| 39 | **Category flexfields** — multi-set category model (Oracle `category_sets`) replacing flat tree | product-svc | Single flat `parent_id` tree only |
| 40 | **Open Item Interface** — bulk import endpoint (CSV/JSON) for variants | product-svc | No bulk import endpoint or batch job |

### Tier 3 — POS completeness

| # | Gap | Service | Notes |
|---|---|---|---|
| 41 | **Price overrides at POS** — ad-hoc override endpoint + `price_overrides` append-only table | pricing-svc | Named price lists exist; no per-transaction POS override |
| 42 | **Special orders** — customer order placed at store for future delivery | order-svc | No endpoint or table |
| 43 | **POSLog / transaction journal** — full POSLog record per completed order | order-svc | `tax_transactions` in pricing-svc is POSLog-compatible; order-svc has no POSLog entity |
| 44 | **Receipt / e-journal printing** — receipt model + print/email endpoint | order-svc | Nothing in codebase |
| 45 | **POS session idle timeout** — idle timeout detection + force-logout in iam-svc | iam-svc | JWT refresh exists; no idle-session timeout |
| 46 | **Tax-exempt flag on orders** — order-svc must pass and record the `exempt` flag returned by pricing-svc `/prices/resolve` | order-svc + pricing-svc | pricing-svc resolves it; order-svc ignores it |

### Tier 4 — Reporting & multi-org

| # | Gap | Service | Notes |
|---|---|---|---|
| 47 | **Multi-org quantity report** — cross-store aggregate on-hand view | reporting-svc *(new)* | `aggregateDemand` is per-store only; no cross-store rollup |
| 48 | **Item supply / demand netting** — on-hand + open POs + open orders combined view | reporting-svc | On-hand only; no supply/demand netting query |
| 49 | **Movement statistics** — aggregated demand history at tenant level across all stores | reporting-svc | Raw `stock_movements` per store; no tenant-level rollup |
| 50 | **SIM ↔ POS sync** — Store Inventory Management event bridge between inventory-svc and order-svc | inventory-svc + order-svc | Not modeled anywhere |
| 51 | **Inter-org shipping network / shipping methods** — route table between stores + method reference | inventory-svc / tenant-svc | Transfer orders exist but no shipping route or method model |
| 52 | **Multi-entity accounting / economic zones** — extend intercompany invoicing for group structures | purchase-svc | Intercompany invoices exist; no economic zone or multi-entity model |
| 53 | **Inventory org parameters** — tenant-level profile knobs (enable/disable lot, serial, grade per org) | tenant-svc | No such config knobs in tenant schema |

### Tier 5 — Blockers (post-demo)

| # | Gap | Service | Notes |
|---|---|---|---|
| 54 | **payment-svc** — entire service: card/e-check tender, multi-currency arithmetic, VISA PABP, instant credit enrollment | payment-svc *(new)* | Directory does not exist; blocks all payment flows |
| 55 | **Shortage alerts** — wire `reorder_thresholds` to notification-svc dispatch when stock < min_qty | inventory-svc + notification-svc | Threshold data stored; zero alert code |

---

## Summary

Shelf-J has a solid multi-tenant structural foundation — tenant/store/zone hierarchy, products, inventory batches, reservations, and an append-only stock movement log. The gaps cluster around three themes:

1. **Planning intelligence** — the data is there but no engine reads `reorder_thresholds` and produces replenishment orders (Oracle Ch. 14 is the entire Planning and Replenishment chapter).
2. **Inventory control discipline** — serial tracking, material status, lot genealogy, cycle counting, physical inventory (Oracle Ch. 7–9, 17–18).
3. **POS / commerce layer** — order-svc and pricing-svc are not yet built; the entire Oracle Retail POS feature set is pending.

---

*Reference docs: [Oracle Inventory User's Guide R12.1 TOC](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/toc.htm) · [Planning & Replenishment chapter](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/T291651T292324.htm) · [Oracle Retail POS R1.3.0 Release Notes](https://docs.oracle.com/cd/E12521_01/point_of_service/pdf/130/pos-130-rn.pdf)*
