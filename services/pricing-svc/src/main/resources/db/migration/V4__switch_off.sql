-- SJ-D33: a promotion could be created and never stopped.
--
-- promotions.active has been NOT NULL DEFAULT TRUE since V1, the engine's candidate query filters
-- on it, and nothing ever wrote it -- the only UPDATE statement in the whole of pricing-svc was on
-- vat_rates. There was no PUT, no DELETE and no /deactivate. ends_at is nullable, so a promotion
-- created without one runs forever, and the only way to stop it was direct database access.
--
-- That is the "declared column with no writer" shape for the ninth time on this branch, and the
-- most expensive instance of it, because the thing that could not be switched off is a discount.
--
-- price_lists.active has exactly the same shape and is fixed in the same migration. Fixing only the
-- one that was reported is the mistake this branch has now recorded four times: SJ-D10 closed two
-- endpoints and SJ-D11 found 24 more; SJ-D12 closed one file and SJ-D16 found two more; SJ-D2
-- closed two services and SJ-D23 found the third.

-- Why a table rather than columns on the row, as V3 of purchase-svc used for a cancellation
-- reason: a promotion can be switched off and on again repeatedly, so there is a history, and a
-- record that keeps only the last change cannot answer "who turned this back on?". The same
-- reasoning purchase_order_approvals was built on.
CREATE TABLE promotion_status_changes (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    -- Which switch was thrown. Both tables carry the same defect and the same audit need, so one
    -- trail serves both rather than two that will drift apart.
    subject_type VARCHAR(20) NOT NULL,
    subject_id   UUID        NOT NULL,
    -- What it was changed TO. Recorded as the new state rather than as a verb, so a reader does not
    -- have to know which way "toggled" went.
    active       BOOLEAN     NOT NULL,
    reason       TEXT        NOT NULL,
    changed_by   UUID,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_psc_subject CHECK (subject_type IN ('PROMOTION','PRICE_LIST'))
);

-- The trail for one promotion, newest first -- the query the screen and any dispute both need.
CREATE INDEX idx_psc_subject
    ON promotion_status_changes (tenant_id, subject_type, subject_id, changed_at DESC);

-- Finding what is still running is the query this feature exists to serve. Without it, "show me
-- every live promotion so I can stop the wrong one" is a full scan of every promotion the tenant
-- has ever created.
CREATE INDEX idx_promotions_live
    ON promotions (tenant_id, ends_at) WHERE active = TRUE;
