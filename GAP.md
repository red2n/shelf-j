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
| POS transaction engine | ❌ Missing | `order-svc` planned but not yet built |
| Return / refund transactions | ❌ Missing | No RETURN movement maps to a customer-facing return flow |
| Post-void (cancel completed transaction) | ❌ Missing | No void workflow |
| Layaway (deposit + deferred pickup) | ❌ Missing | — |
| Special orders (customer order at store) | ❌ Missing | — |
| Gift cards (issue / reload / redeem) | ❌ Missing | — |
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
| 1 | **Min-Max planning engine** — automated replenishment suggestions + auto-requisition | inventory-svc | Ch. 14 |
| 2 | **Unit of Measure model** — UOM classes, conversions, lot-UOM | product-svc | Ch. 3 |
| 3 | **Serial number control** — generate, assign, genealogy | inventory-svc | Ch. 8 |
| 4 | **Material status** — Quarantine / Inspection / Damaged / Recall on batches | inventory-svc | Ch. 9 |
| 5 | **Move Orders / Pick Wave** — replenishment move orders, express pick release | inventory-svc | Ch. 13 |
| 6 | **Transfer Orders** — inter-store stock transfers (direct + intransit) | inventory-svc | Ch. 11 |
| 7 | **Demand history aggregation** — periodic buckets from stock_movements | inventory-svc | Ch. 14 |
| ~~8~~ | ~~**Safety stock calculation** (MAD formula + user-defined %)~~ ✅ | ~~inventory-svc~~ | ~~Ch. 14~~ |
| ~~9~~ | ~~**ABC analysis** — compile + assign A/B/C classes for cycle count frequency~~ ✅ | ~~inventory-svc~~ | ~~Ch. 16~~ |
| 10 | **Cycle counting** — schedule, count entry, tolerance approval, adjustment | inventory-svc | Ch. 17 |
| 11 | **Lot genealogy** — parent/child chain on batches | inventory-svc | Ch. 7 |
| 12 | **Item revisions** | product-svc | Ch. 5 |
| 13 | **Item templates** — bulk attribute assignment | product-svc | Ch. 4 |
| 14 | **POS transaction engine** — return, post-void, layaway, gift card | order-svc | POS RN |
| 15 | **Tax model** — tax rates, tax-exempt flags, POSLog capture | pricing-svc | POS RN |
| 16 | **Physical inventory reconciliation** — snapshot, tags, adjustments | inventory-svc | Ch. 18 |
| 17 | **Costing methods** (FIFO / average) + accounting period close | inventory-svc or new costing-svc | Ch. 15 |
| 18 | **Kanban replenishment** (all 4 types: Supplier / Inter-Org / Intra-Org / Production) | inventory-svc | Ch. 14 |
| 19 | **Reorder Point planning with EOQ** | inventory-svc | Ch. 14 |
| 20 | **Intercompany invoicing** — AR/AP on inter-org transfers | purchase-svc / payment-svc | Ch. 19 |

---

## Summary

Shelf-J has a solid multi-tenant structural foundation — tenant/store/zone hierarchy, products, inventory batches, reservations, and an append-only stock movement log. The gaps cluster around three themes:

1. **Planning intelligence** — the data is there but no engine reads `reorder_thresholds` and produces replenishment orders (Oracle Ch. 14 is the entire Planning and Replenishment chapter).
2. **Inventory control discipline** — serial tracking, material status, lot genealogy, cycle counting, physical inventory (Oracle Ch. 7–9, 17–18).
3. **POS / commerce layer** — order-svc and pricing-svc are not yet built; the entire Oracle Retail POS feature set is pending.

---

*Reference docs: [Oracle Inventory User's Guide R12.1 TOC](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/toc.htm) · [Planning & Replenishment chapter](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/T291651T292324.htm) · [Oracle Retail POS R1.3.0 Release Notes](https://docs.oracle.com/cd/E12521_01/point_of_service/pdf/130/pos-130-rn.pdf)*
