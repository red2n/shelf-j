-- Custom roles (readiness review 20.10): a tenant's own roles, each standing on one of the built-in
-- tiers and holding a subset of that tier's permissions.
--
-- The five built-in roles are not rows here: they are the tiers, and every gate in the platform
-- reads them from the token as it always has. A custom role can only narrow the tier it stands on
-- — "a manager who cannot void a sale", "a cashier who cannot open the drawer" — never widen it,
-- so nothing a tier refuses by path becomes reachable by naming a permission.
CREATE TABLE tenant_roles (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    -- The tenant's own code, upper snake case: SHIFT_LEAD, TRAINEE. Never one of the tier names.
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    -- MANAGER | STOREKEEPER | CASHIER: what the token carries, and what the path gates read.
    base_tier   TEXT NOT NULL,
    -- The permission codes held, comma-separated; '' for a role narrowed to nothing.
    permissions TEXT NOT NULL DEFAULT '',
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT chk_tenant_roles_tier CHECK (base_tier IN ('MANAGER','STOREKEEPER','CASHIER'))
);
CREATE INDEX idx_tenant_roles_tenant ON tenant_roles (tenant_id);

-- An assignment keeps naming the role it was made with in `role` (a tier, or now a custom code),
-- and says which tier that is in `base_tier`, which is what iam-svc binds.
ALTER TABLE staff_assignments ADD COLUMN base_tier TEXT;
UPDATE staff_assignments SET base_tier = role;
ALTER TABLE staff_assignments ALTER COLUMN base_tier SET NOT NULL;
CREATE INDEX idx_staff_role ON staff_assignments (tenant_id, role);
