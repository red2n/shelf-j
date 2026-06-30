-- Split/partial tenders (e.g. POS cash+card) each publish their own PaymentCaptured event for
-- less than the order total. Previously each event was checked in isolation against the full
-- order total and dropped if it fell short, so a split-tender sale never confirmed. paid_amount
-- accumulates captured tenders so the order confirms once they sum to the total.
ALTER TABLE orders ADD COLUMN paid_amount NUMERIC(18,2) NOT NULL DEFAULT 0;

-- Idempotency ledger for PaymentCaptured events: Kafka redelivery of the same paymentId must not
-- double-count it into paid_amount (golden rule #7 — event consumers are idempotent).
CREATE TABLE order_payment_events (
    tenant_id  UUID NOT NULL,
    payment_id UUID NOT NULL,
    order_id   UUID NOT NULL,
    amount     NUMERIC(18,2) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, payment_id)
);
CREATE INDEX idx_order_payment_events_order ON order_payment_events (tenant_id, order_id);
