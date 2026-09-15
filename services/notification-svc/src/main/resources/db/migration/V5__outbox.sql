-- The transactional outbox (golden rule 6), which this service had not needed: it consumed and
-- sent, and announced nothing. Retention (21.16) is the first thing it has to tell another service —
-- each purge of the notification log is announced to tenant-svc's register, and the announcement is
-- written in the purge's own transaction so a run is never recorded that did not happen, nor
-- happens unrecorded.
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
