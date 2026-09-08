# Reporting API — Tenant vs. Super-Admin Gap Analysis

**Date:** 2026-06-19 · **Updated:** 2026-09-07

> **Status change.** Four of the five reports listed below as unbuilt have since shipped, and not
> in reporting-svc. Each was built where its data actually lives: shrinkage, valuation and
> low-stock in inventory-svc, tax summary in pricing-svc. reporting-svc's projections carry
> neither the reason code, the actor, the cost basis nor the tax journal, so a report built there
> would have had to re-project data the owning service already holds. Only CSV export and
> cross-tenant reporting remain untouched.

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
| `GET /admin/reports/{name}/export` (CSV) | **still open** | Worth doing once across every report rather than per report |

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
| CSV export | Yes | No | — |
| Cross-tenant / platform-wide reporting | No (undefined) | No | — |
