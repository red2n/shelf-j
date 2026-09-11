-- SJ-D43: a customer erased by a shop must lose their name, phone and address from that shop's
-- orders too — and customer-svc's erasure used to stop at its own customers row.
--
-- An order that is still open keeps its delivery details until it is finished: they are needed to
-- deliver it, and performing the contract is a lawful reason to delay erasure. So the erasure is
-- recorded here, and a sweeper redacts each open order once it reaches a settled state. The sale
-- itself — amounts, tax, lines — stays: tax law requires it. Only what identifies the person goes.
CREATE TABLE customer_erasures (
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    event_id    UUID        NOT NULL,
    erased_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_customer_erasures PRIMARY KEY (tenant_id, customer_id)
);

-- Redaction finds a customer's parked sales and layaways by customer; neither table had an index
-- that could. (special_orders already has idx_special_orders_tenant_cust.)
CREATE INDEX IF NOT EXISTS idx_parked_sales_customer
    ON parked_sales (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_layaways_customer
    ON layaways (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
