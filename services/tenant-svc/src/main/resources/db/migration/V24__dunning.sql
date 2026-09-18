-- 21.12 Dunning and suspend-for-non-payment.
--
-- An overdue invoice is chased on a schedule, then the platform is taken away, then the debt is given
-- up on. Each step happens once however many times the run is run: UNIQUE (invoice_id, step) is what
-- says so, not a check somebody remembered to write.
--
-- The decision that shapes this file: paying up must reactivate a business the platform suspended for
-- non-payment, and must never reactivate one an administrator switched off. Until now tenants.status
-- recorded ACTIVE or INACTIVE and nothing about why, so the two were indistinguishable and a payment
-- would have quietly overruled a decision somebody took.

-- What the platform's tolerance is. A singleton, like platform_billing_profile: one row, id = 1, and
-- the database enforces it.
--
-- The defaults are from published dunning practice rather than invented: reminders on days 1, 3, 5
-- and 7 after the due date (three or four attempts over ten to fourteen days recovers most of what is
-- recoverable), service interrupted at fourteen, the debt given up on at thirty. Configurable,
-- because a platform's own tolerance is a commercial decision and not a technical one.
CREATE TABLE dunning_policy (
    id                       SMALLINT    PRIMARY KEY,
    enabled                  BOOLEAN     NOT NULL,
    -- Days after the due date, ascending, as a comma-separated list: 1,3,5,7. A list rather than
    -- columns because a platform may want three reminders or five, and adding a column for each would
    -- make the policy a migration instead of a setting.
    reminder_days            TEXT        NOT NULL,
    suspend_after_days       INTEGER     NOT NULL,
    uncollectible_after_days INTEGER     NOT NULL,
    updated_by               UUID        NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_dunning_singleton CHECK (id = 1),
    CONSTRAINT ck_dunning_reminders CHECK (reminder_days ~ '^[0-9]+(,[0-9]+)*$'),
    -- Suspending before the last reminder has been sent would take the platform away from a business
    -- that has not yet been told it is late, and giving up before suspending would write off a debt
    -- the platform never stopped serving. The order is part of the policy, so the database holds it.
    CONSTRAINT ck_dunning_order CHECK (uncollectible_after_days > suspend_after_days),
    CONSTRAINT ck_dunning_suspend CHECK (suspend_after_days BETWEEN 1 AND 365),
    CONSTRAINT ck_dunning_write_off CHECK (uncollectible_after_days BETWEEN 2 AND 730)
);

-- Deliberately not seeded. A seed row would need an updated_by, and the platform's rule is UUIDv7
-- minted with Ids.newId() — never a literal in SQL, which the integration-test audit also refuses. So
-- the defaults live in code (Dunning.DEFAULT_POLICY) and an absent row means "the defaults", the same
-- way Entitlements treats a business on no plan as unrestricted. updated_by is then only ever written
-- when a person actually set the policy, which is the only time the question "who?" has an answer.

-- What has been done about one overdue invoice, append-only. The unique index is the idempotency:
-- a run that runs twice, or two replicas running at the same instant, chase once.
CREATE TABLE dunning_events (
    id         UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    invoice_id UUID        NOT NULL REFERENCES billing_invoices (id),
    -- REMINDER_1 … REMINDER_n, SUSPENDED, UNCOLLECTIBLE, DUE_DATE_EXTENDED, RESOLVED
    step       TEXT        NOT NULL,
    detail     TEXT,
    -- Null when the run did it; the administrator when a human did.
    actor_id   UUID,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_dunning_step CHECK (step ~ '^[A-Z][A-Z0-9_]*$')
);

-- One step per invoice, ever. A reminder that has been sent is not sent again because the run ran
-- again; an extension is the exception and carries the day it was extended to in its step.
CREATE UNIQUE INDEX uq_dunning_step ON dunning_events (invoice_id, step)
    WHERE step <> 'DUE_DATE_EXTENDED';
CREATE INDEX idx_dunning_tenant ON dunning_events (tenant_id, created_at DESC);
CREATE INDEX idx_dunning_invoice ON dunning_events (invoice_id, created_at);

-- Why a business was switched off, and by whom.
--
-- Without this, "pay your bill and the platform comes back" cannot tell a business the platform
-- suspended from one an administrator suspended, and a payment would lift both. Only NON_PAYMENT is
-- ever lifted by money; everything else stays exactly as it is, and the payment is still recorded.
ALTER TABLE tenants ADD COLUMN deactivated_reason TEXT;
ALTER TABLE tenants ADD COLUMN deactivated_by UUID;
ALTER TABLE tenants ADD COLUMN deactivated_at TIMESTAMPTZ;

ALTER TABLE tenants ADD CONSTRAINT ck_tenant_deactivated_reason
    CHECK (deactivated_reason IS NULL OR deactivated_reason IN ('NON_PAYMENT', 'ADMINISTRATOR'));

-- A business that is off has a reason; one that is on has none. Stated here so the pair cannot drift:
-- a reason left behind on a reactivated business would make the next payment lift a suspension nobody
-- asked it to lift.
ALTER TABLE tenants ADD CONSTRAINT ck_tenant_deactivated_pair
    CHECK ((status = 'INACTIVE') OR (deactivated_reason IS NULL));

COMMENT ON COLUMN tenants.deactivated_reason IS
    'NON_PAYMENT (dunning, lifted by paying up) or ADMINISTRATOR (never lifted by a payment).';
