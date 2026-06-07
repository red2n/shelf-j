-- Idempotency guard for event consumers: dedupe by event id (golden rule #7).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
