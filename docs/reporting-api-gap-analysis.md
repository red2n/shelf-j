# Reporting API — Tenant vs. Super-Admin Gap Analysis

**Date:** 2026-06-19 · **Updated:** 2026-09-08

> **Status change.** Every report listed below as unbuilt has since shipped, and none of them in
> reporting-svc. Each was built where its data actually lives: shrinkage, valuation, low-stock,
> stock turn and dead-stock ageing in inventory-svc; tax summary in pricing-svc; tender mix in
> payment-svc; sales by hour and by staff in order-svc. reporting-svc's projections carry neither
> the reason code, the actor, the cost basis, the tax journal, the tender nor the cashier, so a
> report built there would have had to re-project data the owning service already holds. CSV
> export shipped too, on the client rather than as an endpoint per report. Only cross-tenant
> reporting remains untouched, and only gross margin and sales-by-category remain unanswerable —
> see the note below.

## Tenant-level reporting — exists, but partial

Three endpoints live in `services/reporting-svc`, all tenant-scoped (`tenant_id` read from JWT via `TenantContext`, verified isolated in `ReportingIT.tenantIsolation()`):

| Endpoint | Returns |
|---|---|
| `GET /admin/reports/inventory/on-hand` | On-hand qty per store/variant + grand total |
| `GET /admin/reports/inventory/supply-demand` | On-hand + in-transit supply, net available |
| `GET /admin/reports/inventory/movement-stats` | Inbound/outbound qty bucketed by day/week/month |

Backed by `inventory_projection`, `movement_events`, `open_supply_lines` tables (`services/reporting-svc/src/main/resources/db/migration/V1__init.sql`). Access gated by `AdminAuthorizationFilter` (`shared/common-web/src/main/java/com/shelfj/web/AdminAuthorizationFilter.java:41-43`) to roles `OWNER`/`MANAGER`/`PLATFORM_ADMIN`.

Source: `services/reporting-svc/src/main/java/com/shelfj/reporting/api/AdminResource.java:17-66`.

## Designed but not built — mostly resolved

| Report | Where it landed | Why not reporting-svc |
|---|---|---|
| `GET /admin/reports/sales` | reporting-svc — sales by day, sales summary | n/a, built where designed |
| `GET /admin/inventory/reports/valuation` | inventory-svc | Needs the cost basis and FIFO batch costs; the projection has neither |
| `GET /admin/inventory/reports/low-stock` | inventory-svc | Needs each item's own reorder level, safety stock and par level |
| `GET /admin/inventory/reports/shrinkage` | inventory-svc | Needs `reason_code` and `actor_id`; also covers cycle-count variances, which publish no event |
| `GET /admin/reports/tax-summary` | pricing-svc | Reads `tax_transactions`, the same rows the MTD VAT return computes from, so the two reconcile |
| `GET /admin/inventory/reports/stock-turn` | inventory-svc | Needs the cost of the batches each sale drew down; the projection carries no batch and no cost |
| `GET /admin/inventory/reports/dead-stock` | inventory-svc | Same — plus the last-sale date per (store, variant), which only the movement ledger has |
| `GET /admin/reports/tender-mix` | payment-svc | `sales_facts` holds one gross amount per order and no tender, so a split payment is a single row there |
| `GET /admin/reports/sales-by-hour` | order-svc | Needs the order rows themselves, bucketed in a caller-chosen timezone |
| `GET /admin/reports/sales-by-staff` | order-svc | Reads the POS transaction journal, the only table that knows who served a customer |
| `GET /admin/reports/{name}/export` (CSV) | shipped on the client | Done once in the admin app across every report, rather than as an endpoint per report |

Cross-service reporting remains reporting-svc's job; a report answerable entirely inside one
service's own schema belongs in that service (golden rule #1 constrains reading *other* services'
tables, not reporting on your own).

## Super-admin / cross-tenant reporting — does not exist

A `PLATFORM_ADMIN` role exists (seeded in `services/iam-svc/src/main/resources/db/migration/V1__init.sql:82-88`) and is used elsewhere — e.g. `tenant-svc`'s `PlatformResource.java` for activating/suspending tenants, and a POS session sweep in `AuthIT.java`. But:

- It's currently lumped into the same `MANAGEMENT_ROLES` set that gates the tenant-scoped reporting endpoints — it doesn't unlock anything extra for reporting.
- There is no `/platform/reports/**` (or similar) path, no cross-tenant aggregation query, and no design-doc section calling for platform-wide reporting. This is an undefined gap, not just an unimplemented one — it would need to be scoped from scratch.

## Summary table

| Capability | Designed? | Implemented? | Scope |
|---|---|---|---|
| On-hand inventory snapshot | Yes | Yes | Tenant |
| Supply/demand netting | Yes | Yes | Tenant |
| Movement statistics | Yes | Yes | Tenant |
| Sales reports | Yes | Yes | Tenant |
| Inventory valuation | Yes | Yes | Tenant |
| Low-stock alerts | Yes | Yes | Tenant |
| Stock write-off / shrinkage | No (added) | Yes | Tenant |
| Tax/GST summary | Yes | Yes | Tenant |
| Stock turn | No (added) | Yes | Tenant |
| Dead-stock ageing | No (added) | Yes | Tenant |
| Tender mix | No (added) | Yes | Tenant |
| Sales by hour | No (added) | Yes | Tenant |
| Sales by staff | No (added) | Yes | Tenant |
| CSV export | Yes | Yes (client-side) | Tenant |
| Gross margin / GMROI | Yes | **No** — see below | Tenant |
| Sales by category | Yes | **No** — see below | Tenant |
| Cross-tenant / platform-wide reporting | No (undefined) | No | — |

## The two that are genuinely blocked, and on what

Every other report on this page was buildable because one service already owned all of its data.
These two are not, and saying why is more useful than listing them as "todo".

**Gross margin and GMROI** need revenue and cost of goods sold in the same place. order-svc owns
revenue and has no cost; inventory-svc computes COGS exactly (see `StockTurnRepository`) and has no
revenue. Neither can read the other's tables (golden rule #1), and neither should make a synchronous
call to the other on a reporting path.

The way through is an event, not a join: `StockDeducted` already fires from inventory-svc on every
sale and already carries the order it was for. Enriching it with the cost actually drawn down would
let reporting-svc — which already consumes both that topic and `OrderConfirmed` — accumulate a
`cogs_amount` alongside the `gross_amount` it already keeps in `sales_facts`. That is where gross
margin belongs, because it is the one report here that genuinely spans two services.

One thing that design has to get right: orders confirmed before the enriched event ships would carry
no COGS. Reporting them at 100% margin would be worse than not reporting them, so the coverage has to
be declared the way `unvaluedQty` and `uncostedSaleQty` already are elsewhere on this page.

**Sales by category** needs the variant→category mapping, which product-svc owns and does not
publish — there is no catalogue topic on the bus at all. It needs a `ProductCategorised`-shaped event
and a projection in reporting-svc before the report itself is worth starting.
