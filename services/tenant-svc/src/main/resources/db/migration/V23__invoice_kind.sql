-- An invoice is either a period or an adjustment to one (21.9 defect).
--
-- uq_invoices_period exists so a *period* cannot be billed twice, and it is the right constraint for
-- that. But it was written over (subscription_id, period_start) with nothing to say which invoices are
-- periods, so it also caught a proration: an upgrade on the day a business signs up raises an
-- adjustment whose period_start is the same day as the first period's invoice, and the index refused
-- it. The live flow found it on its first run — the upgrade answered 500.
--
-- A proration is not a period. It is an adjustment to one, it can happen more than once in a period,
-- and it must not compete with the period's own invoice for a slot. So the invoice says which it is,
-- and the constraint applies to periods alone.
ALTER TABLE billing_invoices ADD COLUMN kind TEXT;

-- Everything raised so far is a period: prorations could not be written at all until now.
UPDATE billing_invoices SET kind = 'PERIOD' WHERE kind IS NULL;

ALTER TABLE billing_invoices ALTER COLUMN kind SET NOT NULL;

-- No DEFAULT left behind: every INSERT names the kind, the way every INSERT names its id. A default
-- would let a new caller raise an adjustment that silently claims a period's slot, which is the bug
-- this migration exists to fix.
ALTER TABLE billing_invoices ADD CONSTRAINT ck_invoices_kind
    CHECK (kind IN ('PERIOD', 'ADJUSTMENT'));

DROP INDEX uq_invoices_period;

-- One invoice per period per subscription, counting only the invoices that are a period. A withdrawn
-- one frees its slot, as before: it keeps its number but it is no longer the period's invoice.
CREATE UNIQUE INDEX uq_invoices_period ON billing_invoices (subscription_id, period_start)
    WHERE status <> 'VOID' AND kind = 'PERIOD';

-- What an adjustment is read by: the period it adjusts, newest first.
CREATE INDEX idx_invoices_adjustments ON billing_invoices (subscription_id, period_start)
    WHERE kind = 'ADJUSTMENT';

COMMENT ON COLUMN billing_invoices.kind IS
    'PERIOD (the run raised it, one per period) or ADJUSTMENT (a proration, any number per period).';
