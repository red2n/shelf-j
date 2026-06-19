# Reporting API — Tenant vs. Super-Admin Gap Analysis

**Date:** 2026-06-19

## Tenant-level reporting — exists, but partial

Three endpoints live in `services/reporting-svc`, all tenant-scoped (`tenant_id` read from JWT via `TenantContext`, verified isolated in `ReportingIT.tenantIsolation()`):

| Endpoint | Returns |
|---|---|
| `GET /admin/reports/inventory/on-hand` | On-hand qty per store/variant + grand total |
| `GET /admin/reports/inventory/supply-demand` | On-hand + in-transit supply, net available |
| `GET /admin/reports/inventory/movement-stats` | Inbound/outbound qty bucketed by day/week/month |

Backed by `inventory_projection`, `movement_events`, `open_supply_lines` tables (`services/reporting-svc/src/main/resources/db/migration/V1__init.sql`). Access gated by `AdminAuthorizationFilter` (`shared/common-web/src/main/java/com/shelfj/web/AdminAuthorizationFilter.java:41-43`) to roles `OWNER`/`MANAGER`/`PLATFORM_ADMIN`.

Source: `services/reporting-svc/src/main/java/com/shelfj/reporting/api/AdminResource.java:17-66`.

## Designed but not built (per README §9.12 / PRD §4.12)

- `GET /admin/reports/sales` (by day/store/channel/product)
- `GET /admin/reports/inventory-valuation`
- `GET /admin/reports/low-stock`
- `GET /admin/reports/tax-summary`
- `GET /admin/reports/{name}/export` (CSV)

None of these have endpoints, repository methods, or backing tables — the schema only covers the three inventory reports above.

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
| Sales reports | Yes | No | Tenant |
| Inventory valuation | Yes | No | Tenant |
| Low-stock alerts | Yes | No | Tenant |
| Tax/GST summary | Yes | No | Tenant |
| CSV export | Yes | No | — |
| Cross-tenant / platform-wide reporting | No (undefined) | No | — |
