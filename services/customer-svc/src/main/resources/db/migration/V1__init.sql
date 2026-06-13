-- customer-svc schema: customer profiles, addresses, loyalty, store credit.
-- Golden rule #3: tenant_id is always the first index column.
-- Golden rule #8: loyalty_ledger and store_credit_ledger are append-only — no UPDATE/DELETE.

CREATE TABLE customers (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    email            TEXT        NOT NULL,
    phone            TEXT,
    first_name       TEXT        NOT NULL,
    last_name        TEXT        NOT NULL,
    dob              DATE,
    gender           TEXT,                           -- M | F | OTHER | PREFER_NOT
    status           TEXT        NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | SUSPENDED | ANONYMIZED
    gdpr_consent_at  TIMESTAMPTZ,                    -- when marketing consent was given
    anonymized_at    TIMESTAMPTZ,                    -- set on GDPR erasure; email/phone zeroed
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);
CREATE INDEX idx_customers_tenant        ON customers (tenant_id, status, created_at DESC);
CREATE INDEX idx_customers_tenant_phone  ON customers (tenant_id, phone) WHERE phone IS NOT NULL;

CREATE TABLE customer_addresses (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL REFERENCES customers(id),
    type        TEXT        NOT NULL DEFAULT 'HOME',  -- HOME | BILLING | SHIPPING
    line1       TEXT        NOT NULL,
    line2       TEXT,
    city        TEXT,
    state       TEXT,
    country     TEXT        NOT NULL,
    pincode     TEXT,
    is_default  BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_addresses_tenant_cust ON customer_addresses (tenant_id, customer_id);

-- One loyalty account per customer per tenant. Points balance is denormalized here for fast reads;
-- the append-only ledger is the authoritative source of truth.
CREATE TABLE loyalty_accounts (
    id               UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    customer_id      UUID          NOT NULL REFERENCES customers(id),
    points_balance   NUMERIC(18,2) NOT NULL DEFAULT 0,
    lifetime_points  NUMERIC(18,2) NOT NULL DEFAULT 0,
    tier             TEXT          NOT NULL DEFAULT 'BRONZE',  -- BRONZE|SILVER|GOLD|PLATINUM
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id)
);
CREATE INDEX idx_loyalty_accounts_tenant ON loyalty_accounts (tenant_id, tier);

-- Append-only: never UPDATE or DELETE rows.
CREATE TABLE loyalty_ledger (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    customer_id   UUID          NOT NULL,
    type          TEXT          NOT NULL,             -- EARN | REDEEM | EXPIRE | ADJUST
    points        NUMERIC(18,2) NOT NULL,             -- positive = earned, negative = redeemed/expired
    balance_after NUMERIC(18,2) NOT NULL,
    order_id      UUID,
    reason        TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_loyalty_ledger_tenant_cust ON loyalty_ledger (tenant_id, customer_id, created_at DESC);

-- One store-credit account per customer per tenant per currency.
CREATE TABLE store_credit_accounts (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    customer_id UUID          NOT NULL REFERENCES customers(id),
    balance     NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency    TEXT          NOT NULL DEFAULT 'GBP',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, customer_id, currency)
);
CREATE INDEX idx_store_credit_accounts_tenant ON store_credit_accounts (tenant_id, customer_id);

-- Append-only: never UPDATE or DELETE rows.
CREATE TABLE store_credit_ledger (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    customer_id   UUID          NOT NULL,
    type          TEXT          NOT NULL,             -- ISSUE | REDEEM | EXPIRE | ADJUST
    amount        NUMERIC(18,2) NOT NULL,             -- positive = issued, negative = redeemed
    balance_after NUMERIC(18,2) NOT NULL,
    currency      TEXT          NOT NULL DEFAULT 'GBP',
    order_id      UUID,
    reason        TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_credit_ledger_tenant_cust ON store_credit_ledger (tenant_id, customer_id, created_at DESC);

-- Transactional outbox (golden rule #6 — event + DB write are atomic).
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
