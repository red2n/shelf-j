-- iam-svc schema. Identity for BOTH staff and customers.
--
-- Tenant-scoping note (README §9.1): staff belong to a tenant (tenant_id set); customers are global
-- (tenant_id NULL — a customer may shop any storefront). So users.tenant_id is intentionally NULLABLE,
-- a deliberate exception to the usual NOT NULL rule. Staff queries still filter by tenant_id.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    tenant_id     UUID,                              -- NULL for CUSTOMER, set for STAFF
    type          TEXT NOT NULL,                     -- STAFF | CUSTOMER
    email         TEXT,
    phone         TEXT,
    password_hash TEXT,                              -- Argon2; NULL if OTP-only
    status        TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | DISABLED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- A given email/phone is unique within a tenant scope (NULL tenant = the global/customer scope).
CREATE UNIQUE INDEX uq_users_tenant_email ON users (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'), lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_tenant_phone ON users (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'), phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_users_tenant ON users (tenant_id, status);

CREATE TABLE roles (
    id    UUID PRIMARY KEY,
    name  TEXT NOT NULL UNIQUE                       -- PLATFORM_ADMIN, OWNER, MANAGER, STOREKEEPER, CASHIER, CUSTOMER
);

CREATE TABLE user_roles (
    id        UUID PRIMARY KEY,
    user_id   UUID NOT NULL REFERENCES users(id),
    role_id   UUID NOT NULL REFERENCES roles(id),
    store_id  UUID,                                  -- optional: role scoped to a store
    UNIQUE (user_id, role_id, store_id)
);
CREATE INDEX idx_user_roles_user ON user_roles (user_id);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    token_hash TEXT NOT NULL,                        -- store a hash, never the raw token
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id, revoked);
CREATE UNIQUE INDEX uq_refresh_hash ON refresh_tokens (token_hash);

CREATE TABLE otp_codes (
    id         UUID PRIMARY KEY,
    target     TEXT NOT NULL,                        -- email or phone the code was sent to
    code_hash  TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed   BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_target ON otp_codes (target, consumed);

-- Append-only audit log.
CREATE TABLE audit_log (
    id         UUID PRIMARY KEY,
    tenant_id  UUID,
    user_id    UUID,
    action     TEXT NOT NULL,                        -- USER_REGISTERED, LOGIN_OK, LOGIN_FAILED, ...
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant ON audit_log (tenant_id, created_at DESC);

-- Transactional outbox: events written in the same tx as the state change, drained to Kafka.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,                       -- JSON
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- Seed the standard roles.
INSERT INTO roles (id, name) VALUES
    (gen_random_uuid(), 'PLATFORM_ADMIN'),
    (gen_random_uuid(), 'OWNER'),
    (gen_random_uuid(), 'MANAGER'),
    (gen_random_uuid(), 'STOREKEEPER'),
    (gen_random_uuid(), 'CASHIER'),
    (gen_random_uuid(), 'CUSTOMER');
