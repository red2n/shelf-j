-- payment-svc schema
-- Golden rule #8: payments and refunds are append-only.

CREATE TABLE IF NOT EXISTS payment_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    method          VARCHAR(30) NOT NULL,   -- CASH | CARD | GIFT_CARD | VOUCHER
    reference       VARCHAR(255),           -- card auth code, gift-card code, etc.
    idempotency_key VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'CAPTURED', -- CAPTURED | FAILED
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS refund_tenders (
    id              UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    order_id        UUID        NOT NULL,
    payment_id      UUID        NOT NULL,
    amount          NUMERIC(14,4) NOT NULL,
    method          VARCHAR(30) NOT NULL,
    reference       VARCHAR(255),
    idempotency_key VARCHAR(255),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id              UUID        NOT NULL PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    tenant_id       UUID,
    aggregate_id    UUID        NOT NULL,
    payload         TEXT        NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox (created_at) WHERE published_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_idempotency
    ON payment_tenders (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_idempotency
    ON refund_tenders (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Tenant index for fast order-scoped queries
CREATE INDEX IF NOT EXISTS idx_payment_tenders_order
    ON payment_tenders (tenant_id, order_id);

CREATE INDEX IF NOT EXISTS idx_refund_tenders_order
    ON refund_tenders (tenant_id, order_id);

CREATE INDEX IF NOT EXISTS idx_refund_tenders_payment
    ON refund_tenders (tenant_id, payment_id);
