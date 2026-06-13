-- POS cash management: pay-in / pay-out (petty cash) and daily Z-report settlement.

-- ── Pay-in / Pay-out (petty cash) ────────────────────────────────────────────
-- Distinct from cash_drops (safe drops). Pay-out = petty cash expense taken from drawer.
-- Pay-in = cash added to drawer for non-sale reasons (e.g. change fund replenishment).
-- Append-only: no UPDATE/DELETE.
CREATE TABLE IF NOT EXISTS cash_movements (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    till_session_id UUID          NOT NULL,
    direction       TEXT          NOT NULL,   -- PAY_IN | PAY_OUT
    amount          NUMERIC(14,4) NOT NULL,
    reason          TEXT          NOT NULL,
    authorised_by   UUID,                     -- supervisor UUID when required
    recorded_by     UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_cash_movements_session
    ON cash_movements (tenant_id, till_session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_movements_store
    ON cash_movements (tenant_id, store_id, created_at DESC);

-- ── Daily Z-report ────────────────────────────────────────────────────────────
-- One row per store per business day. Created when the last till for the day is
-- Z-closed. Summarises all tender types and cash movements for reconciliation.
-- Immutable once created (the day is settled — no corrections, use adjustments).
CREATE TABLE IF NOT EXISTS z_reports (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    business_date     DATE          NOT NULL,
    total_sales       NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_refunds     NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_discounts   NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_tax         NUMERIC(18,2) NOT NULL DEFAULT 0,
    net_sales         NUMERIC(18,2) NOT NULL DEFAULT 0,   -- total_sales - total_refunds
    cash_sales        NUMERIC(18,2) NOT NULL DEFAULT 0,
    card_sales        NUMERIC(18,2) NOT NULL DEFAULT 0,
    gift_card_sales   NUMERIC(18,2) NOT NULL DEFAULT 0,
    other_sales       NUMERIC(18,2) NOT NULL DEFAULT 0,
    opening_float     NUMERIC(14,4) NOT NULL DEFAULT 0,
    cash_drops        NUMERIC(14,4) NOT NULL DEFAULT 0,
    pay_ins           NUMERIC(14,4) NOT NULL DEFAULT 0,
    pay_outs          NUMERIC(14,4) NOT NULL DEFAULT 0,
    expected_cash     NUMERIC(14,4) NOT NULL DEFAULT 0,   -- computed at close
    counted_cash      NUMERIC(14,4),                      -- entered by manager
    over_short        NUMERIC(14,4),                      -- counted - expected
    transaction_count INTEGER       NOT NULL DEFAULT 0,
    currency          TEXT          NOT NULL DEFAULT 'GBP',
    generated_by      UUID,
    generated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, store_id, business_date)
);
CREATE INDEX IF NOT EXISTS idx_z_reports_store
    ON z_reports (tenant_id, store_id, business_date DESC);
