-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
-- customer-svc gained its first Kafka consumer (order-confirmed → loyalty accrual); the shared
-- BaseJdbcRepository.markProcessedIfNew* helpers write here so a redelivered event accrues once.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
