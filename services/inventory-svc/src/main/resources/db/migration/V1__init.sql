-- inventory-svc schema: the stock source of truth. README §9.4.
-- References store_id/variant_id from other services but NEVER joins their tables (database-per-service).
-- Quantities are NUMERIC (exact). stock_movements is APPEND-ONLY (no UPDATE/DELETE paths).

CREATE TABLE inventory_batches (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    store_id      UUID NOT NULL,
    variant_id    UUID NOT NULL,
    batch_no      TEXT,
    received_qty  NUMERIC(18,3) NOT NULL,
    remaining_qty NUMERIC(18,3) NOT NULL,            -- the ONLY mutable quantity field
    cost_price    NUMERIC(18,2),
    expiry_date   DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- FIFO scan order: soonest expiry first, then oldest. Index supports the deduction query.
CREATE INDEX idx_batches_fifo ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at);

-- Append-only ledger of every stock movement.
CREATE TABLE stock_movements (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    store_id   UUID NOT NULL,
    variant_id UUID NOT NULL,
    batch_id   UUID,
    type       TEXT NOT NULL,                         -- RECEIVE|SALE|ADJUST|TRANSFER|RETURN|RESERVE|RELEASE
    qty        NUMERIC(18,3) NOT NULL,                -- signed: +in / -out
    ref_type   TEXT,                                  -- e.g. GRN, ORDER, ADJUSTMENT
    ref_id     UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_movements_tenant ON stock_movements (tenant_id, store_id, variant_id, created_at DESC);

CREATE TABLE reservations (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    store_id   UUID NOT NULL,
    variant_id UUID NOT NULL,
    qty        NUMERIC(18,3) NOT NULL,
    order_id   UUID,
    status     TEXT NOT NULL DEFAULT 'HELD',          -- HELD|CONSUMED|RELEASED
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_expiry ON reservations (status, expires_at) WHERE status = 'HELD';

-- Per-(store,variant) reorder threshold → drives LowStock. Generic + optional.
CREATE TABLE reorder_thresholds (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    store_id   UUID NOT NULL,
    variant_id UUID NOT NULL,
    threshold  NUMERIC(18,3) NOT NULL,
    UNIQUE (tenant_id, store_id, variant_id)
);

-- Idempotency guard for event consumers (dedupe on event id).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
