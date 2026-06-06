---
name: onboard-tenant
description: Implement or walk through Shelf-J client (tenant) onboarding and the Tenant→Stores→Zones location-mapping flow. Use when building tenant-svc/iam-svc onboarding, the setup checklist, store/zone creation, staff assignment, or delivery-area mapping.
---

# Client onboarding & location mapping (Tenant → Stores → Zones)

Use when implementing or reasoning about how a **new business (tenant)** gets set up and how **physical locations** are modeled. This flow shapes data in many services, so keep it consistent.

> **Authoritative design:** [docs/onboarding-and-locations.md](../../../docs/onboarding-and-locations.md). Read it before changing the model. Also: [CLAUDE.md location model](../../../CLAUDE.md), tenant-svc/iam-svc specs in [README §9](../../../README.md#9-the-business-services--full-catalog).

## The model (don't deviate without updating downstream)

```
Tenant (business)  →  Store/Warehouse (address+geo+hours)  →  Zone (aisle/rack/cold-room/...)
```
- Tenant has ≥1 store; store has ≥1 zone (a `DEFAULT` zone is auto-created so stock always has a home).
- **Inventory, pricing, orders, POS are scoped to a store; stock lives in a `(store, zone)`.**
- **tenant-svc owns** `tenants`, `stores`, `zones`, `staff_assignments`, `delivery_areas`. Other services reference `store_id`/`zone_id` but **never join** those tables — they consume `StoreCreated`/`ZoneCreated` events or call tenant-svc.

## Onboarding happy path (implement in this order)

1. **Owner signs up** — iam-svc `POST /auth/register` → STAFF user (no tenant yet) → JWT. Publishes `UserRegistered`.
2. **Create business** — tenant-svc `POST /onboarding/tenants {businessName, country, currency, planId}` → tenant `PENDING`→`ACTIVE`, link owner. Publishes `TenantCreated` (with `ownerUserId`). **iam-svc consumes it** and stamps `tenant_id` + `OWNER` role on the user (reflected in the next JWT refresh).
3. **Create first store** — tenant-svc `POST /onboarding/stores {name, code, type, address, geo, timezone, businessHours}` → store `is_default=true` **and auto-create its `DEFAULT` zone**. Publishes `StoreCreated` + `ZoneCreated`.
4. **(optional) Map real zones** — `POST /admin/stores/{id}/zones` for aisles/racks/cold-room. Publishes `ZoneCreated` each.
5. **(optional) Invite staff** — `POST /admin/staff {userEmail, storeId, role}`; tenant-svc verifies/invites via iam-svc → `staff_assignments`. Publishes `StaffAssigned`.
6. **Setup checklist** — `GET /onboarding/status` reports: tenant ACTIVE ✓, default store ✓, default zone ✓, (optional) products, (optional) staff. Drives the admin-console wizard.

## Hard rules specific to this flow

- **`tenant_id` from JWT only.** On `POST /onboarding/tenants` the caller has no tenant yet — link the tenant to the **authenticated user id**, then stamp tenant on the user via `TenantCreated`. Everywhere after, `tenant_id` comes from the JWT; reject mismatched body/path tenant (`403`).
- **Codes unique per scope:** store `code` unique per tenant; zone `code` unique per store. Validate and return `409` on clash.
- **Always guarantee a home for stock:** never let a store exist without a `DEFAULT` zone.
- **Events via outbox; consumers idempotent** (re-delivered `TenantCreated` must not create a second OWNER role, etc.).
- **Downstream services cache, not join:** they may keep a local `(store_id → tenant_id, geo, status)` read-model from `StoreCreated`/`ZoneCreated`. Never a cross-service DB join.

## Location mapping downstream (wire these correctly)

- **Put-away on receipt:** purchase-svc GRN → `GoodsReceived` → inventory-svc creates a batch in `(store_id, zone_id)`; default to the store's `DEFAULT` zone if none chosen.
- **POS:** cashier bound to a store via `staff_assignments`; sale deducts that store's stock (FIFO).
- **Online delivery fulfilment:** at checkout (`DELIVERY`), order-svc calls tenant-svc `GET /fulfilment/resolve?pincode=` to choose the fulfilling store (lowest `priority` wins). `PICKUP` ⇒ customer-chosen store. (Delivery-area mapping may be deferred — see the design doc's phasing note.)

## Build/verify checklist

- [ ] tenant-svc tables per [design §3](../../../docs/onboarding-and-locations.md) (Flyway, tenant_id, indexes, UTC, NUMERIC).
- [ ] Onboarding endpoints + `GET /onboarding/status`.
- [ ] Auto-create `DEFAULT` zone with every store.
- [ ] `TenantCreated`/`StoreCreated`/`ZoneCreated`/`StaffAssigned` published via outbox; iam-svc stamps tenant/role idempotently.
- [ ] Unique-code validation (`409`); tenant-mismatch rejection (`403`).
- [ ] Testcontainers test: register → create tenant → create store(+default zone) → assign staff; assert tenant isolation and idempotent event replay.
- [ ] Open decisions acknowledged ([design §9](../../../docs/onboarding-and-locations.md#9-open-decisions-carry-to-prd-11) / [PRD §11](../../../PRD.md)).
