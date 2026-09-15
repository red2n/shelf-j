-- Deferred revenue for loyalty points and gift card breakage (readiness review 17.11).
--
-- FRS 102 section 23, as revised by the 2024 periodic review for periods from 1 January 2026,
-- takes IFRS 15's five-step model, and two things this ledger did not do follow from it. A point a
-- shopper earns with a purchase is a separate promise, so part of the sale's revenue belongs to it;
-- and a gift card sold is a liability, part of which the business expects never to be claimed.
-- Until now loyalty never reached the ledger, selling a gift card posted nothing, and only spending
-- one did (Dr 2310 from the tender, 17.7).
--
--   points earned with a sale   Dr 4010 sales / Cr 2330 deferred income: the sale's net revenue
--                               split between the goods and the points by standalone selling
--                               price, the points at their value less expected breakage
--   points given away           Dr 6410 loyalty points awarded / Cr 2330
--   points spent                Dr 2330 / Cr 4020 their share of what is deferred against the
--                               points expected to be spent; when none remain, the rest Cr 4030
--   a gift card sold, reloaded  Dr the tender's control account (6420 when given away) / Cr 2310
--   a gift card spent           Dr 2310 / Cr 4031 breakage, in proportion to what is spent
--
-- The value of a point and the two breakage estimates are the tenant accountant's settings. An
-- event about points that arrives before they are set is kept, and posted the moment they are.

-- Append-only: the estimates in force are the latest row, and every earlier one stays as evidence
-- of what the accounts were prepared on.
CREATE TABLE deferred_revenue_settings (
    id                     UUID          PRIMARY KEY,
    tenant_id              UUID          NOT NULL,
    currency               CHAR(3)       NOT NULL,
    point_value            NUMERIC(14,4) NOT NULL CHECK (point_value > 0),
    points_breakage_pct    NUMERIC(5,2)  NOT NULL CHECK (points_breakage_pct BETWEEN 0 AND 95),
    gift_card_breakage_pct NUMERIC(5,2)  NOT NULL CHECK (gift_card_breakage_pct BETWEEN 0 AND 95),
    reason                 TEXT          NOT NULL,
    set_by                 UUID,
    set_at                 TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_drs_tenant_set_at ON deferred_revenue_settings (tenant_id, set_at DESC);

-- Every announcement customer-svc made about points, posted or waiting for the settings. The row
-- is the consumer's dedupe as well: an event is recorded once.
CREATE TABLE loyalty_events (
    tenant_id   UUID        NOT NULL,
    event_id    UUID        NOT NULL,
    kind        VARCHAR(10) NOT NULL CHECK (kind IN ('EARNED','REDEEMED','ADJUSTED')),
    customer_id UUID,
    order_id    UUID,
    points      NUMERIC     NOT NULL,
    order_total NUMERIC,
    order_tax   NUMERIC,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at   TIMESTAMPTZ,
    journal_id  UUID,
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX idx_loyalty_events_waiting ON loyalty_events (tenant_id, received_at, event_id)
    WHERE posted_at IS NULL;

-- The tenant's points as the ledger follows them. Locked for every change, so concurrent events
-- cannot release the same income twice.
CREATE TABLE loyalty_point_pools (
    tenant_id          UUID        PRIMARY KEY,
    points_outstanding NUMERIC     NOT NULL CHECK (points_outstanding >= 0),
    deferred_income    NUMERIC     NOT NULL CHECK (deferred_income >= 0),
    points_unmatched   NUMERIC     NOT NULL CHECK (points_unmatched >= 0),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The tenant's gift cards since the ledger began to follow them: what breakage may be recognised
-- against. Cards loaded before this migration are outside it, so they earn no breakage.
CREATE TABLE gift_card_pools (
    tenant_id  UUID        PRIMARY KEY,
    loaded     NUMERIC     NOT NULL CHECK (loaded >= 0),
    redeemed   NUMERIC     NOT NULL CHECK (redeemed >= 0),
    breakage   NUMERIC     NOT NULL CHECK (breakage >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A gift card load as order-svc announced it: one row per card transaction, so a load announced
-- twice is posted once.
CREATE TABLE gift_card_loads (
    tenant_id      UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    gift_card_id   UUID        NOT NULL,
    store_id       UUID,
    kind           VARCHAR(10) NOT NULL CHECK (kind IN ('ISSUE','RELOAD')),
    paid_by        VARCHAR(20) NOT NULL,
    amount         NUMERIC     NOT NULL CHECK (amount > 0),
    currency       CHAR(3)     NOT NULL,
    journal_id     UUID        NOT NULL,
    loaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, transaction_id)
);
