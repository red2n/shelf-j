-- Gap #45: POS session idle timeout — track cashier sessions; sweep idle ones and revoke tokens.

CREATE TABLE pos_sessions (
    id                   UUID        PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    store_id             UUID        NOT NULL,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at             TIMESTAMPTZ,
    idle_timeout_seconds INTEGER     NOT NULL DEFAULT 900,  -- 15 minutes default
    status               TEXT        NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE | ENDED | EXPIRED
);
CREATE INDEX idx_pos_sessions_tenant_user   ON pos_sessions (tenant_id, user_id, status);
CREATE INDEX idx_pos_sessions_tenant_store  ON pos_sessions (tenant_id, store_id, status);
CREATE INDEX idx_pos_sessions_idle_sweep    ON pos_sessions (last_activity_at)
    WHERE status = 'ACTIVE';
