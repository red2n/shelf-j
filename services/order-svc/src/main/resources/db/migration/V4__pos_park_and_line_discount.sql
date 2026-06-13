-- POS register operations: parked (suspended) sales, no-sale/open-drawer log,
-- line-level discounts on order_items, customer_id FK linkage.

-- ── Line-level discount on order_items ────────────────────────────────────────
-- Allows marking down individual lines independently of the order-level discount.
ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_reason TEXT;

-- ── Parked (suspended) sales ──────────────────────────────────────────────────
-- A cashier can park an in-progress sale and resume it later (e.g. to serve the
-- next customer while the first one fetches their loyalty card).
-- Parked sales are DRAFT state; they become orders when resumed and tendered.
CREATE TABLE IF NOT EXISTS parked_sales (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    cashier_id      UUID,
    customer_id     UUID,
    customer_name   TEXT,
    subtotal        NUMERIC(18,2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    notes           TEXT,
    parked_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,                          -- optional auto-expiry
    resumed_at      TIMESTAMPTZ,                          -- set when converted to an order
    order_id        UUID                                  -- the order created on resume
);
CREATE INDEX IF NOT EXISTS idx_parked_sales_tenant
    ON parked_sales (tenant_id, store_id, parked_at DESC)
    WHERE resumed_at IS NULL;

CREATE TABLE IF NOT EXISTS parked_sale_items (
    id           UUID          PRIMARY KEY,
    tenant_id    UUID          NOT NULL,
    sale_id      UUID          NOT NULL REFERENCES parked_sales(id) ON DELETE CASCADE,
    variant_id   UUID          NOT NULL,
    qty          NUMERIC(18,3) NOT NULL,
    unit_price   NUMERIC(18,2) NOT NULL,
    line_total   NUMERIC(18,2) NOT NULL,
    discount_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    notes        TEXT
);
CREATE INDEX IF NOT EXISTS idx_parked_items_tenant_sale
    ON parked_sale_items (tenant_id, sale_id);

-- ── No-sale / open-drawer log (append-only) ───────────────────────────────────
-- Records every non-transactional cash-drawer open (no-sale) and any manager
-- override that required a supervisor PIN. Loss-prevention audit trail.
CREATE TABLE IF NOT EXISTS pos_no_sale_log (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    store_id     UUID        NOT NULL,
    cashier_id   UUID,
    till_session_id UUID,
    reason       TEXT,
    authorised_by UUID,                    -- supervisor UUID when override required
    logged_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_no_sale_log_tenant_store
    ON pos_no_sale_log (tenant_id, store_id, logged_at DESC);
