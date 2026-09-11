-- Supplier invoices and the three-way match (readiness review, horizon 2 item 5).
--
-- The match compares three figures: what was ORDERED, what was RECEIVED, and what the supplier has
-- INVOICED. It is the control that stops a business paying for goods it did not order, did not get,
-- or was charged the wrong price for — and it is the reason procurement software exists at all.
--
-- Two of those three legs only became trustworthy on this branch, which is why this could not have
-- been built earlier:
--
--   ordered   SJ-D22. total_net/vat/gross were inserted as zero and never written again, so every
--             purchase order in the product reported a value of 0.00. A match against that would
--             have compared every invoice to nothing.
--   received  Partial receipt. A goods receipt used to close the whole order regardless of quantity
--             and then REFUSE the next delivery, so "how much has actually arrived" could not be
--             represented for any order that came in more than one lorry.
--   invoiced  This migration. Nothing existed.
--
-- intercompany_invoices is deliberately untouched and unrelated: that is store-to-store inside one
-- tenant, with no supplier and nothing to match against.

CREATE TABLE supplier_invoices (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    -- Every invoice here is against a purchase order. Non-PO invoices (services, utilities) are a
    -- real thing and deliberately out of scope: they have no ordered or received leg, so they are
    -- not a three-way match and modelling them here would make the po_id nullable and every match
    -- query answer "it depends".
    po_id           UUID        NOT NULL REFERENCES purchase_orders(id),
    supplier_id     UUID        NOT NULL REFERENCES suppliers(id),
    -- The supplier's own reference, as printed on the document. Unique per supplier so the same
    -- invoice cannot be entered twice by two people on the same morning — the commonest way a
    -- business pays once too often.
    invoice_number  TEXT        NOT NULL,
    invoice_date    DATE        NOT NULL,
    currency        CHAR(3)     NOT NULL,
    -- Unconstrained NUMERIC, per SJ-D25: NUMERIC(14,2) is a statement about sterling rather than
    -- about money, and silently truncated the third decimal of a dinar.
    net_amount      NUMERIC     NOT NULL,
    vat_amount      NUMERIC     NOT NULL DEFAULT 0,
    gross_amount    NUMERIC     NOT NULL,
    -- MATCHED   every line agreed with the order and the receipt, inside tolerance
    -- FLAGGED    at least one line did not — see supplier_invoice_lines.variances
    -- The invoice is stored either way. Flagging does not block capture: an invoice that arrived is
    -- a fact, and refusing to record it because it disagrees with the order loses the evidence of
    -- the disagreement.
    status          VARCHAR(20) NOT NULL,
    matched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_si_status CHECK (status IN ('MATCHED','FLAGGED'))
);

-- The duplicate-payment guard. Case-insensitive because a supplier's own reference is printed on
-- paper and typed by a human: INV-1001 and inv-1001 are the same document, and treating them as two
-- is exactly the failure this index exists to prevent.
CREATE UNIQUE INDEX uq_supplier_invoices_number
    ON supplier_invoices (tenant_id, supplier_id, lower(invoice_number));

CREATE INDEX idx_supplier_invoices_po ON supplier_invoices (tenant_id, po_id);
CREATE INDEX idx_supplier_invoices_status ON supplier_invoices (tenant_id, status);

CREATE TABLE supplier_invoice_lines (
    id            UUID    PRIMARY KEY,
    tenant_id     UUID    NOT NULL,
    invoice_id    UUID    NOT NULL REFERENCES supplier_invoices(id),
    variant_id    UUID    NOT NULL,
    qty_invoiced  NUMERIC(14,3) NOT NULL,
    -- A trade unit price legitimately carries more precision than the currency's minor unit —
    -- 1,000 screws at 0.0125 each — so this is unconstrained too (SJ-D25).
    unit_price    NUMERIC NOT NULL,
    vat_code      VARCHAR(10) NOT NULL DEFAULT 'T1',
    -- The match outcome for this line, as a comma-separated list of variance codes, empty when the
    -- line agreed. Stored rather than recomputed on read because it is the figure a decision was
    -- made against: the purchase order can be amended afterwards, and an invoice that silently
    -- re-matches against the amended order would erase the disagreement it was flagged for. The
    -- same reasoning the approval trail uses for capturing authority at decision time.
    variances     TEXT    NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_supplier_invoice_lines_inv
    ON supplier_invoice_lines (tenant_id, invoice_id, variant_id);

-- Cumulative invoiced quantity per variant across every invoice on an order — the same shape the
-- received leg needs, and for the same reason: a supplier who delivers in two lorries invoices in
-- two documents, and matching each one against the whole order in isolation would flag the second
-- as over-invoiced every time.
CREATE INDEX idx_supplier_invoice_lines_variant
    ON supplier_invoice_lines (tenant_id, variant_id);
