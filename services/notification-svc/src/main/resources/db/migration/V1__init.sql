-- notification-svc schema

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    consumer VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shortage_alerts (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    store_id    UUID NOT NULL,
    variant_id  UUID NOT NULL,
    available   NUMERIC(19,4) NOT NULL,
    threshold   NUMERIC(19,4) NOT NULL,
    event_id    UUID NOT NULL,
    alerted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shortage_alerts_tenant ON shortage_alerts (tenant_id, alerted_at DESC);
CREATE INDEX idx_shortage_alerts_store  ON shortage_alerts (tenant_id, store_id, alerted_at DESC);
