-- Sales read-model (CQRS projection), built from order/payment events. Previously reporting-svc
-- only projected inventory; there was no revenue reporting despite OrderConfirmed / PaymentRefunded
-- being on the bus. One row per confirmed order; refunds accumulate so net = gross - refunded.
CREATE TABLE sales_facts (
    tenant_id       UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    store_id        UUID,
    channel         TEXT,                          -- ONLINE | POS
    customer_id     UUID,
    gross_amount    NUMERIC(18,2) NOT NULL,
    refunded_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency        TEXT          NOT NULL,
    confirmed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, order_id)
);

-- Reports filter by tenant + a confirmed_at date range, optionally narrowing by store/channel.
CREATE INDEX idx_sales_facts_tenant_confirmed ON sales_facts (tenant_id, confirmed_at);
