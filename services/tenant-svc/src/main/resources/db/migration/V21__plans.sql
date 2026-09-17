-- Plans and packaging (21.8). The platform's price list: what a business can be sold, for how much
-- in which currency, and what it includes. These are the platform's tables, the same for every
-- business, so they carry no tenant_id; what a business is ON is tenants.plan_id, a placeholder
-- since V1 that now points at something, and how it got there is tenant_plan_changes.
--
-- A price is never edited: a new price takes effect from a date and the old one stays, because an
-- invoice raised last month must still be explicable next year (21.9 bills from this table).

CREATE TABLE plans (
    id               UUID        PRIMARY KEY,
    code             TEXT        NOT NULL,   -- STARTER, GROWTH: what a person and an API call say
    name             TEXT        NOT NULL,
    description      TEXT,
    status           TEXT        NOT NULL,   -- DRAFT: being written; ACTIVE: sold; RETIRED: kept by who has it, sold to nobody
    billing_interval TEXT        NOT NULL,   -- MONTH | YEAR
    trial_days       INTEGER     NOT NULL,
    is_default       BOOLEAN     NOT NULL,   -- the plan a new business starts on
    is_public        BOOLEAN     NOT NULL,   -- shown on the public price list
    sort_order       INTEGER     NOT NULL,
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_plans_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_plans_interval CHECK (billing_interval IN ('MONTH', 'YEAR')),
    CONSTRAINT ck_plans_trial CHECK (trial_days BETWEEN 0 AND 365),
    -- Nobody starts on a plan that is not sold.
    CONSTRAINT ck_plans_default_is_sold CHECK (NOT is_default OR status = 'ACTIVE')
);

CREATE UNIQUE INDEX uq_plans_code ON plans (upper(code));
-- One default at most.
CREATE UNIQUE INDEX uq_plans_default ON plans (is_default) WHERE is_default;
CREATE INDEX idx_plans_listing ON plans (status, sort_order, code);

CREATE TABLE plan_prices (
    id             UUID          PRIMARY KEY,
    plan_id        UUID          NOT NULL REFERENCES plans (id),
    currency       TEXT          NOT NULL,   -- ISO 4217: a plan is priced in each currency it is sold in
    amount         NUMERIC(18,4) NOT NULL,   -- per billing interval, before tax
    effective_from DATE          NOT NULL,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_plan_prices_amount CHECK (amount >= 0),
    CONSTRAINT ck_plan_prices_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX uq_plan_prices_day ON plan_prices (plan_id, currency, effective_from);

-- What a plan includes. A limit with no value is unlimited; a feature is included or it is not.
-- The keys are a catalogue kept in code (Plans.CATALOGUE): a key nobody enforces would be a promise
-- nobody keeps.
CREATE TABLE plan_entitlements (
    plan_id     UUID    NOT NULL REFERENCES plans (id),
    key         TEXT    NOT NULL,
    limit_value BIGINT,
    enabled     BOOLEAN,
    PRIMARY KEY (plan_id, key),

    CONSTRAINT ck_plan_entitlements_limit CHECK (limit_value IS NULL OR limit_value >= 0),
    -- A row is a limit or a feature, never both.
    CONSTRAINT ck_plan_entitlements_kind CHECK (limit_value IS NULL OR enabled IS NULL)
);

ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_plan FOREIGN KEY (plan_id) REFERENCES plans (id);
CREATE INDEX idx_tenants_plan ON tenants (plan_id) WHERE plan_id IS NOT NULL;

-- How a business came to be on the plan it is on. Append-only.
CREATE TABLE tenant_plan_changes (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenants (id),
    from_plan_id UUID        REFERENCES plans (id),
    to_plan_id   UUID        REFERENCES plans (id),
    changed_by   UUID,                        -- null when the platform did it: a new business put on the default plan
    reason       TEXT,
    changed_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tenant_plan_changes_tenant ON tenant_plan_changes (tenant_id, changed_at DESC);
