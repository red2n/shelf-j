-- sample-svc schema. Demonstrates the tenant-table checklist (README §7.9):
-- tenant_id NOT NULL + composite index starting with tenant_id, UUID PK, timestamptz UTC.

CREATE TABLE IF NOT EXISTS widgets (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_widgets_tenant ON widgets (tenant_id, created_at DESC);
