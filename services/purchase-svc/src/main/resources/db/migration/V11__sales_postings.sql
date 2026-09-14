-- Sales and tender posting to revenue and control accounts (readiness review 17.7).
--
-- The ledger recorded what the business bought and nothing it sold: a till sale, the cash in the
-- drawer, a card payment and a refund reached order-svc and payment-svc and never the books. This
-- service owns the nominal ledger, so it now consumes the three events that describe a sale and
-- posts them through a receipts clearing account, which is how retail systems keep the takings
-- honest when a sale is paid in several parts:
--
--   each tender captured   Dr the tender's control account     Cr 1105 sales receipts clearing
--   the sale confirmed     Dr 1105 clearing (total)            Cr 4010 sales (net), Cr 2200 VAT output
--   a refund               Dr 4010 and 2200 by the sale's VAT ratio (or Dr 1105 when the sale was
--                          never confirmed)                    Cr the refunded tender's control account
--
-- Every posting carries the order as its source, so the clearing account nets to zero per order
-- once a sale is paid and confirmed; anything left open is listed by GET /nominal-ledger/sales-clearing.

-- Consumer-level dedupe, as every consuming service keeps it (golden rule #7).
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A confirmed sale as order-svc announced it: what the refund's VAT share is worked out from.
CREATE TABLE sales_orders (
    tenant_id    UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    store_id     UUID,
    currency     CHAR(3)     NOT NULL,
    total        NUMERIC     NOT NULL,
    tax_amount   NUMERIC     NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, order_id)
);

-- A tender as payment-svc captured it. One row per tender, so a tender announced twice under two
-- event deliveries is posted once.
CREATE TABLE sales_tenders (
    tenant_id   UUID        NOT NULL,
    payment_id  UUID        NOT NULL,
    order_id    UUID        NOT NULL,
    store_id    UUID,
    method      VARCHAR(30),
    amount      NUMERIC     NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, payment_id)
);
CREATE INDEX idx_sales_tenders_order ON sales_tenders (tenant_id, order_id);

-- The clearing report groups the clearing account by order.
CREATE INDEX nle_tenant_code_source ON nominal_ledger_entries (tenant_id, nominal_code, source_ref);
