-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
-- payment-svc now consumes order events (OrderReturned/OrderCancelled → automatic refund); the
-- shared BaseJdbcRepository.markProcessedIfNew* helpers write here so a redelivered order event
-- issues at most one refund.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
