-- SJ-D6: a POS discount was silently discarded, so discounted sales never confirmed.
--
-- The chain, all of it live in the default configuration:
--   1. The till applies a discount and computes what to tender as subtotal - discount, refusing to
--      complete until that reduced amount is fully tendered (tender_screen.dart).
--   2. It places the order with discountAmount set.
--   3. order-svc, with shelfj.order.pricing.enforce=true (the default), sets disc = ZERO and
--      stores total = subtotal + tax -- the full, undiscounted price.
--   4. Tenders are recorded summing to the discounted amount, which is less than that stored
--      total, so paid_amount never covers it and the order is never confirmed.
--   5. PendingOrderSweeper eventually cancels it.
-- The customer has paid, the receipt shows the discount, and the order is cancelled.
--
-- The fix honours the discount under pricing enforcement instead of discarding it. Enforcement
-- still owns unit prices -- the client cannot name its own price -- but a staff-applied discount on
-- top is now accepted, and constrained the way retail constrains it: staff only, never more than
-- the subtotal, never more than the caller's role is authorised for, always with a reason, and
-- always recorded here.
--
-- Append-only (golden rule #8): no UPDATE or DELETE. Modelled on pricing-svc's price_overrides,
-- which already records till price overrides with their actor.
CREATE TABLE order_discounts (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    subtotal        NUMERIC(18,2) NOT NULL,
    discount_amount NUMERIC(18,2) NOT NULL CHECK (discount_amount > 0),
    -- Stored rather than derived so the authority check stays auditable even if the ceilings are
    -- later reconfigured: this is the percentage that was actually granted at the time.
    discount_pct    NUMERIC(6,3)  NOT NULL,
    reason          TEXT          NOT NULL,
    granted_by      UUID,
    -- Which of the caller's roles authorised it -- the one with the highest ceiling. Recorded
    -- because "was this within authority?" cannot be answered later from the user id alone once
    -- role assignments change.
    granted_role    TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_discounts_tenant_order ON order_discounts (tenant_id, order_id);
CREATE INDEX idx_order_discounts_tenant_store ON order_discounts (tenant_id, store_id, created_at DESC);
-- "What has this cashier been discounting?" is the exception-report question.
CREATE INDEX idx_order_discounts_granted_by ON order_discounts (tenant_id, granted_by, created_at DESC)
    WHERE granted_by IS NOT NULL;
