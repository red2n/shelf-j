# Client Onboarding & Location Mapping — Design

> **Status:** Target design (freshly designed for Shelf-J; not yet implemented). This is the authoritative spec for **how a new business (tenant) is onboarded** and **how physical locations are modeled** (Tenant → Stores → Zones). Build to this. Companion: [CLAUDE.md](../CLAUDE.md), [README §9](../README.md#9-the-business-services--full-catalog).

---

## 1. Why this document exists

Two flows sit at the very start of every tenant's life and shape almost every other service's data:

1. **Client (tenant) onboarding** — how a business signs up, gets an owner account, a subscription/plan, and its first store.
2. **Location mapping** — the spatial model (**Tenant → Stores → Zones**) that inventory, pricing, orders, and POS are all scoped to.

Getting these right (and consistent across services) prevents the most expensive class of mistakes: tenant data leaking, stock with no home, or orders that can't be fulfilled. **inventory-svc, pricing-svc, order-svc, and the POS all assume this model — do not change it without updating them together.**

---

## 2. The location model: Tenant → Stores → Zones

```
TENANT  (a business / client)
  │  id, name, status, plan_id
  │
  ├── STORE  (a physical site — owned by tenant-svc)
  │     id, tenant_id, name, type[STORE|WAREHOUSE], code,
  │     address{line1,line2,city,state,country,pincode},
  │     geo{lat,lng}, timezone, business_hours, status, is_default
  │     │
  │     └── ZONE  (internal sub-location within a store — owned by tenant-svc)
  │           id, tenant_id, store_id, name, code,
  │           type[AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY],
  │           status
  │
  └── STORE … (more stores/warehouses)
```

### What each level means

| Level | Meaning | Examples |
|---|---|---|
| **Tenant** | The customer business using the platform. The isolation boundary — all data is `tenant_id`-scoped. | "Sharma Grocers Pvt Ltd" |
| **Store** | A physical site where stock is held and/or sold. `STORE` = customer-facing shop; `WAREHOUSE` = stock only, no walk-in sales. | "MG Road Outlet", "Central Warehouse" |
| **Zone** | A sub-location inside a store telling you *where in the building* stock physically sits. Used to direct put-away (on receipt) and picking. | "Aisle 3", "Cold Room", "Receiving Dock", "Rack B-12" |

### Rules

- **Every tenant has ≥1 store.** Onboarding creates the first store (the **default store**, `is_default = true`).
- **Every store has ≥1 zone.** A `DEFAULT` zone is auto-created with each store so stock always has a home even before the operator maps real aisles.
- **Store `code` is unique per tenant; zone `code` is unique per store.** Codes are short, human-typed identifiers (`MGR`, `WH1`, `A3`) used by staff and on labels.
- **Inventory is scoped to `(store_id, zone_id)`.** A stock batch lives in exactly one zone of one store. Moving stock between zones/stores is a `TRANSFER` movement (append-only) in inventory-svc.
- **Storefront & POS operate in the context of one store.** Online orders resolve a **fulfilling store** (see §6 delivery mapping); POS sales are tied to the cashier's current store.
- **Ownership:** `tenant-svc` **owns** the `tenants`, `stores`, `zones` tables. Other services **reference** `store_id` / `zone_id` but must **never** join to tenant-svc's tables — they obtain store/zone facts via tenant-svc's API or by consuming `StoreCreated` / `ZoneCreated` events (database-per-service, [golden rule #1](../CLAUDE.md)).

---

## 3. Data model (tenant-svc owns these)

> Standard rules apply: UUID PKs, `tenant_id UUID NOT NULL` on tenant-scoped tables, composite index starting with `tenant_id`, timestamps `timestamptz` UTC, Flyway migrations. (`tenants` itself is the root, so `tenant_id` = its own `id`.)

```sql
-- V1__tenants.sql (tenant-svc)
CREATE TABLE tenants (
  id           UUID PRIMARY KEY,
  name         TEXT NOT NULL,
  legal_name   TEXT,
  status       TEXT NOT NULL DEFAULT 'PENDING',   -- PENDING|ACTIVE|SUSPENDED|CLOSED
  plan_id      UUID NOT NULL,
  country      TEXT NOT NULL,                      -- ISO-3166 alpha-2
  currency     TEXT NOT NULL,                      -- ISO-4217
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stores (
  id            UUID PRIMARY KEY,
  tenant_id     UUID NOT NULL REFERENCES tenants(id),
  name          TEXT NOT NULL,
  code          TEXT NOT NULL,                     -- unique per tenant
  type          TEXT NOT NULL DEFAULT 'STORE',     -- STORE|WAREHOUSE
  line1         TEXT, line2 TEXT, city TEXT, state TEXT,
  country       TEXT NOT NULL, pincode TEXT,
  geo_lat       NUMERIC(9,6), geo_lng NUMERIC(9,6),
  timezone      TEXT NOT NULL DEFAULT 'UTC',
  business_hours JSONB,                            -- [{day, open, close}]
  status        TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE|INACTIVE|CLOSED
  is_default    BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);
CREATE INDEX idx_stores_tenant ON stores(tenant_id, status);

CREATE TABLE zones (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  store_id    UUID NOT NULL REFERENCES stores(id),
  name        TEXT NOT NULL,
  code        TEXT NOT NULL,                       -- unique per store
  type        TEXT NOT NULL DEFAULT 'AISLE',       -- AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY|DEFAULT
  status      TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (store_id, code)
);
CREATE INDEX idx_zones_tenant_store ON zones(tenant_id, store_id, status);

-- staff↔store assignment (also tenant-svc)
CREATE TABLE staff_assignments (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL,
  user_id     UUID NOT NULL,                       -- from iam-svc
  store_id    UUID NOT NULL REFERENCES stores(id),
  role        TEXT NOT NULL,                        -- MANAGER|STOREKEEPER|CASHIER
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, store_id, role)
);

-- optional: delivery-zone mapping for online fulfilment (see §6; can defer to a later phase)
CREATE TABLE delivery_areas (
  id          UUID PRIMARY KEY,
  tenant_id   UUID NOT NULL,
  store_id    UUID NOT NULL REFERENCES stores(id), -- which store fulfils this area
  pincode     TEXT,                                 -- simple model: by pincode
  -- geo_polygon GEOGRAPHY,                          -- advanced model (PostGIS), later
  priority    INT NOT NULL DEFAULT 100,             -- lower wins when areas overlap
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_pincode ON delivery_areas(tenant_id, pincode);
```

---

## 4. Onboarding flow (the happy path)

Goal: a new business goes from "sign up" to "ready to receive stock and sell" with minimal steps. Spans **iam-svc** (account) and **tenant-svc** (business + store + zone), coordinated by events.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ STEP 1  Owner signs up                                                        │
│   Client → Gateway → iam-svc  POST /auth/register  {email, phone, password}   │
│   iam-svc creates a STAFF user (type=STAFF, no tenant yet) → JWT issued        │
│   publishes UserRegistered                                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 2  Create the business (tenant)                                          │
│   Owner → Gateway → tenant-svc  POST /onboarding/tenants                        │
│     {businessName, legalName, country, currency, planId}                       │
│   tenant-svc creates tenant(status=PENDING) → links owner user → status=ACTIVE │
│   publishes TenantCreated  (carries ownerUserId)                               │
│   ── iam-svc consumes TenantCreated → stamps tenant_id + OWNER role on the user │
│       (and re-issues a JWT that now contains tenant_id on next refresh)         │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 3  Create the first store (default)                                      │
│   Owner → Gateway → tenant-svc  POST /onboarding/stores                         │
│     {name, code, type, address, geo, timezone, businessHours}                  │
│   tenant-svc creates store(is_default=true)                                    │
│     AND auto-creates a DEFAULT zone for it                                      │
│   publishes StoreCreated  +  ZoneCreated(DEFAULT)                              │
│   ── inventory-svc, pricing-svc, product-svc react: a store now exists they    │
│       can scope data to (they cache store_id; they do NOT read tenant tables).  │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 4  (optional, anytime) Map real zones                                    │
│   Owner/Manager → tenant-svc  POST /admin/stores/{id}/zones  (aisles, racks…)  │
│   publishes ZoneCreated per zone                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 5  (optional, anytime) Invite staff                                      │
│   Owner → tenant-svc  POST /admin/staff {userEmail, storeId, role}             │
│   tenant-svc verifies/【invites】user via iam-svc → staff_assignment            │
│   publishes StaffAssigned                                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ ✔ READY: tenant ACTIVE, ≥1 store with ≥1 zone, owner can add products,         │
│   receive stock (purchase-svc GRN → inventory batches in a zone), and sell.    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Onboarding completeness check** (tenant-svc exposes `GET /onboarding/status`): tenant ACTIVE ✓, default store exists ✓, default zone exists ✓, (optional) products added, (optional) staff invited. The admin console uses this to drive a setup checklist.

---

## 5. APIs (tenant-svc — onboarding & locations)

> All under the gateway. Auth: owner/manager roles. `tenant_id` always from JWT (never body). Standard response envelope.

### Onboarding
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/onboarding/tenants` | authenticated owner (pre-tenant) | Create the business; link caller as OWNER. |
| `POST` | `/onboarding/stores` | OWNER | Create the first/default store (+ DEFAULT zone). |
| `GET`  | `/onboarding/status` | OWNER/MANAGER | Setup-checklist state. |

### Stores
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores` | OWNER | Add a store/warehouse. |
| `GET`  | `/admin/stores` | OWNER/MANAGER | List tenant's stores. |
| `GET`  | `/admin/stores/{id}` | OWNER/MANAGER | Store detail (with zones). |
| `PUT`  | `/admin/stores/{id}` | OWNER/MANAGER | Update address/geo/hours/status. |

### Zones
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores/{storeId}/zones` | OWNER/MANAGER/STOREKEEPER | Add a zone. |
| `GET`  | `/admin/stores/{storeId}/zones` | staff | List zones in a store. |
| `PUT`  | `/admin/zones/{id}` | OWNER/MANAGER/STOREKEEPER | Rename / deactivate. |

### Staff
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/staff` | OWNER/MANAGER | Assign a user to a store with a role. |
| `GET`  | `/admin/staff` | OWNER/MANAGER | List assignments (tenant-scoped). |

### Delivery areas (optional, online fulfilment)
| Method | Path | Role | Purpose |
|---|---|---|---|
| `POST` | `/admin/stores/{storeId}/delivery-areas` | OWNER/MANAGER | Map pincode(s) → this store fulfils them. |
| `GET`  | `/fulfilment/resolve?pincode=` | internal (order-svc) | Resolve which store fulfils a customer pincode. |

---

## 6. Location mapping at work (downstream usage)

How the model is actually used once onboarding is done:

- **Receiving stock (put-away):** purchase-svc records a GRN → `GoodsReceived` → inventory-svc creates a batch in `(store_id, zone_id)`. If the operator doesn't pick a zone, it lands in the store's `DEFAULT` zone.
- **POS sale:** cashier is bound to a store (via `staff_assignments`); the sale deducts stock from that store (FIFO across that store's batches).
- **Online order — fulfilling-store resolution:** at checkout for `DELIVERY`, order-svc calls tenant-svc `GET /fulfilment/resolve?pincode=` to pick the store that serves the customer's pincode (lowest `priority` wins on overlap). For `PICKUP`, the customer chose the store explicitly. Stock is then reserved at that store.
- **Reporting:** sales/inventory facts carry `store_id` (and channel), enabling per-store and cross-store reports.

> **Phasing tip:** delivery-area mapping (the `delivery_areas` table + resolver) can be **deferred**. A simple first cut: single store ⇒ it fulfils everything; multi-store ⇒ require the customer to pick a store, or map by pincode. Geo-polygon (PostGIS) resolution is a later enhancement.

---

## 7. Events (this domain)

| Event | Publisher | Key consumers | Payload essentials |
|---|---|---|---|
| `UserRegistered` | iam-svc | tenant-svc(opt), customer-svc, notification-svc | userId, email, type |
| `TenantCreated` | tenant-svc | iam-svc (stamp tenant_id + OWNER role) | tenantId, ownerUserId, plan |
| `StoreCreated` | tenant-svc | inventory-svc, pricing-svc, product-svc, reporting-svc | tenantId, storeId, type, geo, isDefault |
| `ZoneCreated` | tenant-svc | inventory-svc | tenantId, storeId, zoneId, type |
| `StaffAssigned` | tenant-svc | iam-svc (role/store claim), notification-svc | tenantId, userId, storeId, role |
| `FeatureToggled` | tenant-svc | any feature-gated service | tenantId, featureKey, enabled |

All published via the **transactional outbox**; all consumers **idempotent** (dedupe on event id). See [README §10](../README.md#10-how-services-talk-to-each-other).

---

## 8. Multi-tenancy enforcement recap (do not skip)

- `tenant_id` is read from the **JWT** on every request; **reject** any request whose body/path tries to set a different `tenant_id` (treat as `403`).
- Every query in tenant-svc (and every service) filters `tenant_id` first; store/zone lookups are always `WHERE tenant_id = :jwtTenant AND ...`.
- Cross-service: a service needing store/zone info calls tenant-svc or consumes its events — **never** a DB join. It may keep a **local read-cache** of `(store_id → tenant_id, geo, status)` built from `StoreCreated`/`ZoneCreated` to avoid chatty calls; that cache is its own table, refreshed by events (CQRS projection).
- New tenant tables must follow the [README new-table checklist](../README.md#79-database-rules): `tenant_id NOT NULL`, composite index, and (when DB-level RLS is added) row-level-security policy keyed on the current tenant.

---

## 9. Open decisions (carry to [PRD §11](../PRD.md))

| # | Question | Suggested default |
|---|---|---|
| 1 | Delivery resolution: pincode-only vs geo-polygon (PostGIS)? | Pincode first; PostGIS later. |
| 2 | Can one customer shop multiple tenants' storefronts with one account, or is a customer scoped to a tenant? | Customer scoped per tenant (simpler isolation). |
| 3 | Self-serve onboarding (anyone signs up a business) vs admin-provisioned tenants? | Self-serve, with plan gating. |
| 4 | Auto-create only a `DEFAULT` zone, or seed common zones (Aisle/Cold/Receiving)? | DEFAULT only; let operator add real zones. |
| 5 | Is `WAREHOUSE` sellable via storefront, or stock-only? | Stock-only (not a sales channel). |
