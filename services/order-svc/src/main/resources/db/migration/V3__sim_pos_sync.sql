-- Gap #50 — SIM ↔ POS sync.
-- pos_stock_positions: local stock-level projection updated from inventory-svc events.
-- processed_events:    idempotent deduplication for all Kafka consumers in this service.

CREATE TABLE pos_stock_positions (
    tenant_id   UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    variant_id  UUID          NOT NULL,
    on_hand_qty NUMERIC(18,3) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, store_id, variant_id)
);
CREATE INDEX idx_psp_tenant_store ON pos_stock_positions (tenant_id, store_id);

CREATE TABLE processed_events (
    event_id   UUID        PRIMARY KEY,
    consumer   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
