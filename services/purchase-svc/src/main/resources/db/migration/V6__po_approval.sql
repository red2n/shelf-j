-- Purchase order approval with spend authority limits (readiness review, horizon 2 item 5).
--
-- Until now anyone holding any staff role could commit the business to any amount. /purchase-orders
-- is not under /admin/, so AdminAuthorizationFilter's default-deny for mutations required only
-- "some staff role" — a CASHIER could raise a purchase order for a million pounds and submit it,
-- and nothing anywhere compared the figure to the person. That is the "approval limits and
-- separation of duties" capability the readiness review has listed as absent since the first pass.
--
-- Two things had to exist first, and neither did:
--
--   SJ-D22  the order's total, which was zero on every order ever raised. A spend limit checked
--           against that would have authorised anything.
--   SJ-D23  the order's currency, which was the literal 'GBP'. A limit compared across currencies
--           is worse than no limit: 800,000 JPY against a 5,000 GBP ceiling either blocks every
--           Japanese order or waves through 160 times the intended authority, depending only on
--           which way the mistake runs. That is SJ-D18's sign inversion with a bigger blast radius.
--
-- Both are fixed as of V5, so the threshold below has a real number in a known currency to compare.

-- The order is raised but nobody with the authority to commit this much has agreed to it yet.
-- Deliberately a state and not a boolean: "waiting for a decision" and "decided" are different
-- facts, and a supplier must not receive an order that is merely waiting.
ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS po_status;
ALTER TABLE purchase_orders ADD CONSTRAINT po_status CHECK (status IN (
    'DRAFT',
    'PENDING_APPROVAL',
    'SUBMITTED',
    'PARTIALLY_RECEIVED',
    'RECEIVED',
    'CLOSED',
    'CANCELLED'
));

-- Who raised it. Recorded from the JWT at creation, never from the request body (golden rule #3
-- applies to identity as much as to tenant_id).
--
-- Nullable because orders raised before this migration have no recorded raiser, and inventing one
-- would be worse than admitting none. NULL here means "raised before this was captured", which is
-- exactly what SJ-D4 established for stock_movements.actor_id.
ALTER TABLE purchase_orders
    ADD COLUMN created_by  UUID,
    ADD COLUMN approved_by UUID,
    ADD COLUMN approved_at TIMESTAMPTZ;

-- An approved order must name its approver and when, and an unapproved one must name neither --
-- the same shape V3 used for the cancellation reason, and for the same reason: an approval whose
-- recorded approver is not the one who approved it is worse than no record.
--
-- Scoped to the states an approval decision actually produces. SUBMITTED is reachable two ways --
-- approved, or under the raiser's own authority and never routed for approval -- so it is
-- deliberately absent from both branches.
ALTER TABLE purchase_orders ADD CONSTRAINT po_approved_fields CHECK (
    (status IN ('DRAFT','PENDING_APPROVAL') AND approved_by IS NULL AND approved_at IS NULL)
    OR
    status NOT IN ('DRAFT','PENDING_APPROVAL')
);

-- Append-only (golden rule #8). Columns on the order would have been enough for a single decision,
-- the way V3 handled cancellation -- but a rejection sends the order back to DRAFT to be edited and
-- resubmitted, so one order can cycle through several decisions. Only a table can hold that, and a
-- spend-authority trail that keeps just the last decision is not an audit trail.
CREATE TABLE purchase_order_approvals (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    po_id        UUID        NOT NULL REFERENCES purchase_orders(id),
    decision     VARCHAR(20) NOT NULL,
    -- The figure the decision was actually made against, captured at decision time rather than read
    -- back from the order later: the order can be edited after a rejection, and an approval that
    -- silently re-points at a larger total is the whole attack this feature exists to stop.
    total_net    NUMERIC     NOT NULL,
    currency     CHAR(3)     NOT NULL,
    -- The authority the decider held, so the trail answers "were they allowed to?" without
    -- depending on configuration that has since changed. NULL for a REQUESTED row, which records a
    -- submission rather than a decision.
    authority    NUMERIC,
    decided_by   UUID,
    decided_role VARCHAR(20),
    reason       TEXT,
    decided_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_poa_decision CHECK (decision IN ('REQUESTED','APPROVED','REJECTED')),
    -- A rejection must say why. An approval need not: "yes" is complete on its own, "no" is not,
    -- because only the rejection leaves someone with work to do and no idea what to change.
    CONSTRAINT chk_poa_reason CHECK (decision <> 'REJECTED' OR reason IS NOT NULL)
);

CREATE INDEX idx_poa_po ON purchase_order_approvals (tenant_id, po_id, decided_at DESC);

-- Finding what is waiting for me is the query this feature is used through; without it every
-- approver's landing screen is a full scan of the tenant's purchase orders.
CREATE INDEX idx_po_pending ON purchase_orders (tenant_id, status) WHERE status = 'PENDING_APPROVAL';
