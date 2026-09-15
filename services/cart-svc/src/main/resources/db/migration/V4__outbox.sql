-- The transactional outbox (golden rule 6), which this service had not needed: it consumed and
-- kept, and announced nothing. A departed business's erasure (21.14) is the first thing it has to
-- tell another service: what it erased goes to tenant-svc's evidence, written in the erasure's own
-- transaction so an erasure is never recorded that did not happen, nor happens unrecorded.
CREATE TABLE outbox (
    id           UUID        PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    topic        TEXT        NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
