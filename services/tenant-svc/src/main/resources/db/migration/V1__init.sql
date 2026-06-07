-- tenant-svc schema: the Tenant → Stores → Zones location model + staff (docs/onboarding-and-locations.md §3).
-- delivery_areas is deferred to Phase 2 (when order-svc needs fulfilment routing).

CREATE TABLE tenants (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    legal_name TEXT,
    status     TEXT NOT NULL DEFAULT 'PENDING',      -- PENDING | ACTIVE | SUSPENDED | CLOSED
    plan_id    UUID,
    owner_user_id UUID,                               -- the iam-svc user who created the tenant
    country    TEXT NOT NULL,                         -- ISO-3166 alpha-2
    currency   TEXT NOT NULL,                         -- ISO-4217
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    name           TEXT NOT NULL,
    code           TEXT NOT NULL,                     -- unique per tenant
    type           TEXT NOT NULL DEFAULT 'STORE',     -- STORE | WAREHOUSE
    line1          TEXT, line2 TEXT, city TEXT, state TEXT,
    country        TEXT, pincode TEXT,
    geo_lat        NUMERIC(9,6), geo_lng NUMERIC(9,6),
    timezone       TEXT NOT NULL DEFAULT 'UTC',
    business_hours TEXT,                              -- JSON string
    status         TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | INACTIVE | CLOSED
    is_default     BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_stores_tenant ON stores (tenant_id, status);

CREATE TABLE zones (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    store_id   UUID NOT NULL REFERENCES stores(id),
    name       TEXT NOT NULL,
    code       TEXT NOT NULL,                         -- unique per store
    type       TEXT NOT NULL DEFAULT 'AISLE',         -- AISLE|RACK|SHELF|COLD_ROOM|BACK_STORE|RECEIVING|DISPLAY|DEFAULT
    status     TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, code)
);
CREATE INDEX idx_zones_tenant_store ON zones (tenant_id, store_id, status);

CREATE TABLE staff_assignments (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    user_id    UUID NOT NULL,                         -- from iam-svc
    store_id   UUID NOT NULL REFERENCES stores(id),
    role       TEXT NOT NULL,                         -- MANAGER | STOREKEEPER | CASHIER
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id, store_id, role)
);
CREATE INDEX idx_staff_tenant ON staff_assignments (tenant_id);

-- Transactional outbox.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
