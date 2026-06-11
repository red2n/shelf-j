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
| Item catalog groups / descriptive elements | ✅ Done | V9 migration + full CRUD API |
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
| ~~21~~ | ~~**Transaction reason codes** — codified enum table + `reason_code` column on `stock_movements`~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql + insertReasonCode/listReasonCodes; POST/GET /reason-codes~~ |
| ~~22~~ | ~~**Configurable transaction source types** — `transaction_source_types` table replacing hardcoded TEXT comment~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql + insertSourceType/listSourceTypes; POST/GET /source-types~~ |
| ~~23~~ | ~~**Lot action codes** — split / merge / transfer endpoints + corresponding movements~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql `lot_actions` table; splitLot/mergeLot; POST /lots/split, POST /lots/merge~~ |
| ~~24~~ | ~~**Lot expiry auto-reporting** — scheduled job that alerts on batches past `expiry_date`~~ ✅ | ~~inventory-svc~~ | ~~ExpiryAlertSweeper.java; listExpiringBatches; GET /batches/expiring~~ |
| ~~25~~ | ~~**Lot grade control** — `grade` column on `inventory_batches` + grade-based picking~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql `grade` column; updateBatchGrade; PUT /batches/{id}/grade~~ |
| ~~26~~ | ~~**Lot-specific UOM conversions** — per-lot conversion override table~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql `lot_uom_conversions` table; upsertLotUomConversion; PUT/GET /lots/{batchId}/uom-conversions~~ |
| ~~27~~ | ~~**PAR levels / replenishment counting** — `par_level_configs` table + counting endpoint~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql `par_level_configs`; upsertParLevel/listParLevels; PUT/GET /par-levels~~ |
| ~~28~~ | ~~**Order modifiers** — min/max order qty + fixed-lot multiplier on ROP plans, kanban cards, thresholds~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql columns on `reorder_point_plans` + `kanban_cards`; PUT /rop-plans/{id}/order-modifiers, PUT /kanban-cards/{id}/order-modifiers~~ |
| ~~29~~ | ~~**Reservation batch interface** — bulk `POST /inventory/reservations/batch` endpoint~~ ✅ | ~~inventory-svc~~ | ~~bulkReserve in InventoryService; POST /inventory/reservations/batch~~ |
| ~~30~~ | ~~**Purge transaction history** — admin endpoint + optional scheduled job~~ ✅ | ~~inventory-svc~~ | ~~purgeMovementsBefore in InventoryRepository; POST /movements/purge~~ |
| ~~31~~ | ~~**GL account mapping** — subinventory/zone → nominal code mapping table~~ ✅ | ~~inventory-svc~~ | ~~V17__tier1_gaps.sql `zone_gl_mappings` table; PUT/GET /zone-gl-mappings~~ |

### Tier 2 — Product catalogue richness

| # | Gap | Service | Notes |
|---|---|---|---|
| ~~32~~ | ~~**Item relationships** — substitute / complementary links between variants~~ ✅ | ~~product-svc~~ | ~~V7__item_relationships.sql + Domain/DTO/Repo/Service/Resource; POST/GET/DELETE /products/variants/{id}/relationships; k6 positive + 6 negative cases~~ |
| ~~33~~ | ~~**Supplier / customer cross-references** — supplier part-number cross-ref table~~ ✅ | ~~product-svc~~ | ~~V8__supplier_cross_references.sql; POST/GET/DELETE /variants/{id}/cross-references; positive + negative k6 coverage~~ |
| ~~34~~ | ~~**Manufacturer part numbers** — `manufacturer_pn` field on `product_variants`~~ ✅ | ~~product-svc~~ | ~~V6__manufacturer_pn.sql + Domain/DTO/Repo/Mapper/Service; positive + negative k6 coverage~~ |
| ~~35~~ | ~~**Item catalog groups / descriptive elements** — structured spec metadata beyond `attributes JSONB`~~ ✅ | ~~product-svc~~ | ~~V9__catalog_groups.sql; catalog_groups + catalog_group_elements + variant_catalog_assignments; full CRUD API; positive + negative k6 coverage~~ |
| ~~36~~ | ~~**18 Oracle attribute groups** — typed model for Lead Times, Purchasing, Receiving, WIP, Web, etc.~~ ✅ | ~~product-svc~~ | ~~V10__item_attribute_groups.sql; system-seeded 18 groups + typed fields; variant_attribute_group_values; GET /attribute-groups, PUT/GET/DELETE /variants/{id}/attribute-groups/{groupCode}; k6 positive + negative coverage~~ |
| ~~37~~ | ~~**Container types / cartonization** — container type reference table + variant link~~ ✅ | ~~product-svc~~ | ~~V11__container_types.sql; container_types + variant_container_links; full CRUD + variant linking; k6 positive + negative coverage~~ |
| ~~38~~ | ~~**Picking rules** — configurable pick-sequence rules (FEFO, FIFO, LIFO, FEFO_GRADE, ZONE_PRIORITY)~~ ✅ | ~~inventory-svc~~ | ~~V18__picking_rules.sql; picking_rules + zone_priorities + assignments; `deductFifo` wired to rule engine at consume time; POST/GET /picking-rules, zone-priorities, assignments, resolve preview; k6 positive + negative coverage~~ |
| ~~39~~ | ~~**Category flexfields** — multi-set category model (Oracle `category_sets`) replacing flat tree~~ ✅ | ~~product-svc~~ | ~~V12__category_sets.sql; category_sets + category_set_members + variant_category_set_assignments; full CRUD + variant assignment; k6 positive + negative coverage~~ |
| ~~40~~ | ~~**Open Item Interface** — bulk import endpoint (CSV/JSON) for variants~~ ✅ | ~~product-svc~~ | ~~POST /admin/import; BulkImportRequest/BulkImportResult; partial-success with per-row error array; k6 positive + negative coverage~~ |

### Tier 3 — POS completeness

| # | Gap | Service | Notes |
|---|---|---|---|
| ~~41~~ | ~~**Price overrides at POS** — ad-hoc override endpoint + `price_overrides` append-only table~~ ✅ | ~~pricing-svc~~ | ~~V2__price_overrides.sql; append-only price_overrides; POST/GET /admin/price-overrides; storeId/variantId filter; k6 positive + negative coverage~~ |
| ~~42~~ | ~~**Special orders** — customer order placed at store for future delivery~~ ✅ | ~~order-svc~~ | ~~V2 migration; special_orders + items + status_history; POST/GET /admin/special-orders, /{id}/confirm, fulfil, cancel; k6 coverage~~ |
| ~~43~~ | ~~**POSLog / transaction journal** — full POSLog record per completed order~~ ✅ | ~~order-svc~~ | ~~V2 migration; pos_log_entries (append-only); POST/GET /admin/pos-log/orders/{orderId}; storeId filter; k6 coverage~~ |
| ~~44~~ | ~~**Receipt / e-journal printing** — receipt model + print/email endpoint~~ ✅ | ~~order-svc~~ | ~~V2 migration; order_receipts (append-only); POST/GET /admin/orders/{id}/receipts; PRINT+EMAIL types; k6 coverage~~ |
| ~~45~~ | ~~**POS session idle timeout** — idle timeout detection + force-logout in iam-svc~~ ✅ | ~~iam-svc~~ | ~~V4 migration; pos_sessions; POST/PUT/DELETE/GET /auth/pos/sessions; POST /sweep revokes idle tokens; k6 coverage~~ |
| ~~46~~ | ~~**Tax-exempt flag on orders** — order-svc must pass and record the `exempt` flag~~ ✅ | ~~order-svc~~ | ~~V2 migration ALTER TABLE; taxExempt+exemptReason on Order domain + DTOs; propagated through placeOrder; k6 coverage~~ |

### Tier 4 — Reporting & multi-org

| # | Gap | Service | Notes |
|---|---|---|---|
| 47 | **Multi-org quantity report** — cross-store aggregate on-hand view | reporting-svc *(new)* | `aggregateDemand` is per-store only; no cross-store rollup |
| 48 | **Item supply / demand netting** — on-hand + open POs + open orders combined view | reporting-svc | On-hand only; no supply/demand netting query |
| 49 | **Movement statistics** — aggregated demand history at tenant level across all stores | reporting-svc | Raw `stock_movements` per store; no tenant-level rollup |
| 50 | **SIM ↔ POS sync** — Store Inventory Management event bridge between inventory-svc and order-svc | inventory-svc + order-svc | Not modeled anywhere |
| 51 | **Inter-org shipping network / shipping methods** — route table between stores + method reference | inventory-svc / tenant-svc | Transfer orders exist but no shipping route or method model |
| 52 | **Multi-entity accounting / economic zones** — extend intercompany invoicing for group structures | purchase-svc | Intercompany invoices exist; no economic zone or multi-entity model |
| 53 | ~~**Inventory org parameters** — tenant-level profile knobs (enable/disable lot, serial, grade per org)~~ ✅ | tenant-svc | `tenant_inventory_config` table + `PUT /admin/inventory-config`, `GET /admin/inventory-config` |

### Tier 5 — Blockers (post-demo)

| # | Gap | Service | Notes |
|---|---|---|---|
| 54 | **payment-svc** — entire service: card/e-check tender, multi-currency arithmetic, VISA PABP, instant credit enrollment | payment-svc *(new)* | ✅ Done — cash/card/gift-card tender + refund + PaymentCaptured outbox event |
| ~~55~~ | ~~**Shortage alerts** — wire `reorder_thresholds` to notification-svc dispatch when stock < min_qty~~ ✅ | ~~inventory-svc + notification-svc~~ | ~~`checkThresholdTx` in repo fires within same tx as deduction; StockBelowThreshold outbox event → notification-svc Kafka consumer; `shortage_alerts` table + idempotent dedupe; GET /admin/notifications/shortage-alerts~~ |

---

### Tier 6 — Security (confirmed vulnerabilities — must fix before production)

> Identified by static security analysis of the branch diff. All five findings were independently validated by a false-positive filter with confidence ≥ 8/10. **Items 56 and 57 are pre-deployment blockers — the system cannot be safely exposed without them.**

| # | Severity | Gap | Service | Notes |
|---|---|---|---|---|
| ~~56~~ | ~~🔴 CRITICAL~~ | ~~**Gateway JWT validation absent**~~ ✅ | ~~gateway~~ | ~~`JwtAuthFilter` (priority 999) validates `Authorization: Bearer`, strips spoofed headers, stamps verified X-Tenant-Id/X-User-Id/X-Roles from JWT claims. `GatewayConfig` provides `shelfj.jwt.secret` + `shelfj.jwt.issuer`.~~ |
| ~~57~~ | ~~🔴 HIGH~~ | ~~**No role-based access control on any endpoint**~~ ✅ | ~~all services~~ | ~~`AdminAuthorizationFilter` (common-web, priority 2000) enforces ADMIN/STAFF on all `/admin/**` paths, `POST .../refunds`, `POST .../void`, `POST .../confirm`, `POST .../fulfil`. `TenantContext.requireAnyRole()` available for fine-grained guards.~~ |
| ~~58~~ | ~~🔴 HIGH~~ | ~~**Payment over-refund**~~ ✅ | ~~payment-svc~~ | ~~`PaymentService.recordRefund()` now calls `repo.sumRefunds()` and rejects if cumulative total would exceed original. `idempotency_key` column + unique index added to `refund_tenders`. `PaymentRefunded` event published via outbox.~~ |
| ~~59~~ | ~~🟡 MEDIUM~~ | ~~**Payment tables uninsertable**~~ ✅ | ~~payment-svc~~ | ~~`V1__init.sql` rewritten: `PARTITION BY LIST` removed from `payment_tenders` and `refund_tenders`. Performance indexes added.~~ |
| ~~60~~ | ~~🟡 MEDIUM~~ | ~~**Internal schema leaked in bulk import errors**~~ ✅ | ~~product-svc~~ | ~~`ProductService.bulkImport()` now catches `ApiException` separately and returns sanitized user-facing messages for generic `Exception` (no JDBC details in responses).~~ |

---

## Summary

**Backlog 1 (items 1–20):** ✅ All done — planning engine, UOM, serial control, material status, move/transfer orders, demand history, safety stock, ABC analysis, cycle counting, lot genealogy, item revisions, templates, POS engine, tax/VAT, physical inventory, costing, kanban, ROP with EOQ, intercompany invoicing.

**Backlog 2 Tier 1 (items 21–31):** ✅ All done — reason codes, source types, lot split/merge, expiry sweeper, lot grades, lot-UOM conversions, PAR levels, order modifiers, bulk reservations, history purge, GL zone mapping. All confirmed by V17__tier1_gaps.sql migration and k6 materialControl/planningEngine scenarios.

**Open gaps — 10 items remaining:**

| Tier | Items | Theme |
|---|---|---|
| 2 — Product catalogue | ~~39–40~~ ✅ | Category flexfields, bulk import — both done |
| 3 — POS completeness | ~~41–46~~ ✅ | Price overrides, special orders, POSLog, receipts, POS session idle timeout, tax-exempt — all done |
| 4 — Reporting & multi-org | 47–53 | Cross-store quantity rollup, supply/demand netting, movement statistics, SIM↔POS sync, shipping network/methods, economic zones, inventory org parameters |
| ~~5 — Blockers~~ | ~~55~~ ✅ | ~~Shortage alert dispatch to notification-svc~~ |
| **6 — Security** | **56–60** | **✅ All 5 fixed — gateway JWT (JwtAuthFilter), RBAC (AdminAuthorizationFilter), payment over-refund, payment DDL, bulk import error leak** |

---

*Reference docs: [Oracle Inventory User's Guide R12.1 TOC](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/toc.htm) · [Planning & Replenishment chapter](https://docs.oracle.com/cd/E18727-01/doc.121/e13450/T291651T292324.htm) · [Oracle Retail POS R1.3.0 Release Notes](https://docs.oracle.com/cd/E12521_01/point_of_service/pdf/130/pos-130-rn.pdf)*
