-- Mirrors V21's batch idempotency pattern: a retried POST /inventory/reservations (e.g. order-svc
-- timing out and resubmitting during checkout) must not hold stock twice for one checkout attempt.
ALTER TABLE reservations ADD COLUMN idempotency_key TEXT;
CREATE UNIQUE INDEX idx_reservations_idem ON reservations (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
