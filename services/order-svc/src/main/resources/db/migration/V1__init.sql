-- order-svc schema: saga coordinator for online checkout and in-store POS.
-- Gap #14 POS: post-void, layaway (deposit + deferred pickup), gift cards (issue/reload/redeem).
-- Money is NUMERIC (exact). Append-only tables have no UPDATE/DELETE paths.

CREATE TABLE orders (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    store_id          UUID NOT NULL,
    customer_id       UUID,
    channel           TEXT NOT NULL,                    -- ONLINE | POS
    fulfilment_type   TEXT NOT NULL DEFAULT 'INSTORE',  -- INSTORE | PICKUP | DELIVERY
    status            TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING|CONFIRMED|FULFILLED|CANCELLED|VOIDED
    subtotal          NUMERIC(18,2) NOT NULL,
    tax_amount        NUMERIC(18,2) NOT NULL DEFAULT 0,
    discount_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    total             NUMERIC(18,2) NOT NULL,
    currency          TEXT NOT NULL DEFAULT 'USD',
    notes             TEXT,
    idempotency_key   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_tenant     ON orders (tenant_id, store_id, created_at DESC);
CREATE INDEX idx_orders_customer   ON orders (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE UNIQUE INDEX idx_orders_idem ON orders (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE order_items (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    order_id    UUID NOT NULL REFERENCES orders(id),
    variant_id  UUID NOT NULL,
    qty         NUMERIC(18,3) NOT NULL,
    unit_price  NUMERIC(18,2) NOT NULL,
    line_total  NUMERIC(18,2) NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_items_tenant ON order_items (tenant_id, order_id);

-- Append-only status audit. Never UPDATE or DELETE.
CREATE TABLE order_status_history (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    order_id    UUID NOT NULL,
    from_status TEXT,
    to_status   TEXT NOT NULL,
    reason      TEXT,
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_history_tenant ON order_status_history (tenant_id, order_id, changed_at);

-- ── Returns (Gap #14) ────────────────────────────────────────────────────────

CREATE TABLE returns (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    order_id      UUID NOT NULL,
    store_id      UUID NOT NULL,
    reason        TEXT NOT NULL,
    refund_amount NUMERIC(18,2) NOT NULL,
    refund_method TEXT NOT NULL DEFAULT 'ORIGINAL',   -- ORIGINAL | STORE_CREDIT | GIFT_CARD
    status        TEXT NOT NULL DEFAULT 'COMPLETED',  -- PENDING | COMPLETED | REJECTED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX idx_returns_tenant ON returns (tenant_id, order_id);

CREATE TABLE return_items (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    return_id     UUID NOT NULL REFERENCES returns(id),
    variant_id    UUID NOT NULL,
    qty           NUMERIC(18,3) NOT NULL,
    refund_amount NUMERIC(18,2) NOT NULL,
    condition     TEXT                                 -- NEW | USED | DAMAGED
);
CREATE INDEX idx_return_items_tenant ON return_items (tenant_id, return_id);

-- ── Post-void log (Gap #14) — append-only ────────────────────────────────────

CREATE TABLE pos_void_log (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    order_id   UUID NOT NULL,
    store_id   UUID NOT NULL,
    reason     TEXT,
    voided_by  UUID,
    voided_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pos_void_tenant ON pos_void_log (tenant_id, order_id);

-- ── Layaway (Gap #14) ─────────────────────────────────────────────────────────

CREATE TABLE layaways (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    store_id      UUID NOT NULL,
    customer_id   UUID,
    total_amount  NUMERIC(18,2) NOT NULL,
    deposit_paid  NUMERIC(18,2) NOT NULL DEFAULT 0,
    balance       NUMERIC(18,2) NOT NULL,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE | COMPLETED | CANCELLED
    notes         TEXT,
    due_date      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ
);
CREATE INDEX idx_layaways_tenant   ON layaways (tenant_id, store_id, status);
CREATE INDEX idx_layaways_customer ON layaways (tenant_id, customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE layaway_items (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    layaway_id  UUID NOT NULL REFERENCES layaways(id),
    variant_id  UUID NOT NULL,
    qty         NUMERIC(18,3) NOT NULL,
    unit_price  NUMERIC(18,2) NOT NULL,
    line_total  NUMERIC(18,2) NOT NULL
);
CREATE INDEX idx_layaway_items_tenant ON layaway_items (tenant_id, layaway_id);

-- Append-only deposit ledger.
CREATE TABLE layaway_deposits (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    layaway_id      UUID NOT NULL,
    amount          NUMERIC(18,2) NOT NULL,
    payment_method  TEXT,
    reference       TEXT,
    paid_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_layaway_deposits_tenant ON layaway_deposits (tenant_id, layaway_id, paid_at);

-- ── Gift cards (Gap #14) ──────────────────────────────────────────────────────

CREATE TABLE gift_cards (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    store_id         UUID NOT NULL,
    code             TEXT NOT NULL,
    initial_balance  NUMERIC(18,2) NOT NULL,
    current_balance  NUMERIC(18,2) NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DEPLETED | CANCELLED
    currency         TEXT NOT NULL DEFAULT 'USD',
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_gift_cards_tenant ON gift_cards (tenant_id, code);

-- Append-only balance ledger for each gift card.
CREATE TABLE gift_card_transactions (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    gift_card_id   UUID NOT NULL,
    tx_type        TEXT NOT NULL,    -- ISSUE | RELOAD | REDEEM | REFUND | CANCEL
    amount         NUMERIC(18,2) NOT NULL,
    balance_before NUMERIC(18,2) NOT NULL,
    balance_after  NUMERIC(18,2) NOT NULL,
    order_id       UUID,
    reference      TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gct_tenant ON gift_card_transactions (tenant_id, gift_card_id, created_at);

-- ── Outbox (transactional events) ─────────────────────────────────────────────

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

-- ── Idempotency keys ──────────────────────────────────────────────────────────

CREATE TABLE idempotency_keys (
    key          TEXT,
    tenant_id    UUID NOT NULL,
    response     TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key, tenant_id)
);
