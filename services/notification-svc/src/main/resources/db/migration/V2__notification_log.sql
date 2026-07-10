-- Outbound notification delivery log (N1). Previously notification-svc only recorded shortage
-- alerts and never actually sent anything. Now it consumes user/order events and dispatches a
-- message through a channel (log/SMTP), recording each send here. UNIQUE(event_id, type) makes a
-- redelivered event a no-op so a customer never gets the same welcome/confirmation twice.
CREATE TABLE notification_log (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID,
    event_id    UUID        NOT NULL,
    type        TEXT        NOT NULL,   -- WELCOME | ORDER_CONFIRMATION
    channel     TEXT        NOT NULL,   -- LOG | SMTP
    recipient   TEXT        NOT NULL,
    subject     TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    status      TEXT        NOT NULL,   -- SENT
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, type)
);

CREATE INDEX idx_notification_log_tenant ON notification_log (tenant_id, created_at);
