-- A gapless legal receipt sequence.
--
-- Fiscal law in several markets requires that receipts carry consecutive numbers with no gaps,
-- per till or per store, and that the absence of gaps can be demonstrated to an inspector:
-- Italy's corrispettivi, Germany's KassenSichV, Portugal's certified software regime, Poland,
-- Brazil, and India's e-invoicing. order_receipts already existed but records PRINT and EMAIL
-- events -- how many times a document was produced, not which document it was. A reprint is not a
-- new sale, and a sale with no number is not a receipt.
--
-- Why not a Postgres SEQUENCE: sequences deliberately do not roll back. Two concurrent
-- transactions take 41 and 42, the first aborts, and 41 is gone forever. That is the correct
-- behaviour for a surrogate key and the wrong behaviour for a legal document, where the missing
-- number is exactly what an inspector asks about. A counter row updated inside the caller's
-- transaction gives up concurrency to get that property back, which is the trade every fiscal
-- system makes.

-- One counter per (store, series, period). The series exists so a store can run separate
-- sequences per till where the jurisdiction wants that -- Italy numbers per device -- and the
-- period so numbering restarts each fiscal year, which most of them require.
CREATE TABLE receipt_series (
    tenant_id   UUID   NOT NULL,
    store_id    UUID   NOT NULL,
    series_code TEXT   NOT NULL,
    period      TEXT   NOT NULL,
    -- The next number to hand out. Held here rather than derived from MAX(number) because a
    -- MAX over a table someone can delete from is not a guarantee of anything.
    next_number BIGINT NOT NULL DEFAULT 1,
    -- Printed in front of the number: "GB-LDN-01".
    prefix      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_receipt_series   PRIMARY KEY (tenant_id, store_id, series_code, period),
    CONSTRAINT chk_receipt_next    CHECK (next_number >= 1)
);

-- The documents themselves. Append-only: a receipt is never deleted and never renumbered, and a
-- voided sale keeps its number -- suppressing the number of a cancelled sale is precisely the
-- fraud gapless numbering exists to make visible.
CREATE TABLE fiscal_receipts (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    series_code TEXT        NOT NULL,
    period      TEXT        NOT NULL,
    number      BIGINT      NOT NULL,
    -- What is printed on the document, assembled once and stored rather than reassembled on
    -- read: the prefix can be changed later and a reissued receipt must not come back different.
    full_number TEXT        NOT NULL,
    order_id    UUID        NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_by   UUID,
    currency    CHAR(3)     NOT NULL,
    gross_total NUMERIC(18,4) NOT NULL,
    tax_total   NUMERIC(18,4) NOT NULL,
    voided_at   TIMESTAMPTZ,
    void_reason TEXT,

    -- The gapless guarantee, enforced by the database rather than by the code that means well.
    CONSTRAINT uq_receipt_number UNIQUE (tenant_id, store_id, series_code, period, number),
    -- One receipt per order. A reprint returns the number already issued; issuing a second
    -- document for one sale is how a sale ends up counted twice.
    CONSTRAINT uq_receipt_order  UNIQUE (tenant_id, order_id),
    CONSTRAINT chk_receipt_positive CHECK (number >= 1)
);

-- The inspector's query: every receipt in a series for a period, in order.
CREATE INDEX idx_fiscal_receipts_series
    ON fiscal_receipts (tenant_id, store_id, series_code, period, number);

-- "What did this store take on this day" -- the daily reconciliation.
CREATE INDEX idx_fiscal_receipts_issued
    ON fiscal_receipts (tenant_id, store_id, issued_at DESC);
