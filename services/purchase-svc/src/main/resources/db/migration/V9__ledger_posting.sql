-- The accounting seam (readiness review 17.1, 17.3, 04.7 and the posting half of 07.7).
--
-- Until this migration the nominal ledger had three writers, all intercompany, and nothing a
-- supplier did reached it: goods arrived and no asset was recognised, an invoice was matched and
-- no creditor was recorded, a credit note was recorded and nothing was reversed. The zone-to-GL
-- mapping in inventory-svc was written and read and consumed by nothing. Both are now used:
--
--   goods receipt     Dr Stock (the store's mapped nominal code, or 1001)   Cr 2109 GR/IR
--   supplier invoice  Dr 2109 GR/IR net, Dr 2201 VAT input                  Cr 2100 Creditors
--   credit note       Dr 2100 Creditors                                     Cr Stock, Cr 2201
--   rejection         the invoice's posting, reversed line for line
--   manual journal    whatever finance says, as long as it balances
--
-- The posting model is SAP's rather than "post on approval": an invoice with variances outside
-- tolerance is POSTED and BLOCKED, not held unposted. The liability exists the moment the supplier
-- has invoiced, whatever the buyer thinks of the figures; what a variance stops is payment.

ALTER TABLE nominal_ledger_entries
    -- The lines of one double-entry posting share a journal id, so a journal can be read back
    -- whole and a reversal can be built from it. Rows written before this migration have none.
    ADD COLUMN journal_id  UUID,
    -- What produced the posting: GOODS_RECEIPT, SUPPLIER_INVOICE, INVOICE_REVERSAL, CREDIT_NOTE,
    -- INTERCOMPANY, SETTLEMENT or JOURNAL. source_ref already says which document.
    ADD COLUMN source_type VARCHAR(30),
    -- The store the posting belongs to, so a trial balance can be read per store. Null for a
    -- tenant-level journal and for the intercompany rows written before this column existed.
    ADD COLUMN store_id    UUID;

CREATE INDEX nle_tenant_journal ON nominal_ledger_entries (tenant_id, journal_id);
CREATE INDEX nle_tenant_store_date ON nominal_ledger_entries (tenant_id, store_id, entry_date);

ALTER TABLE supplier_invoices
    -- invoice_date + the supplier's payment terms. The one figure accounts payable actually
    -- schedules by, and it was nowhere.
    ADD COLUMN due_date          DATE,
    -- The total printed on the supplier's document, when the capturer keys it. Compared with the
    -- sum of the supplier's own lines plus VAT; a disagreement is a header variance, because an
    -- invoice that does not add up is wrong before any line is looked at.
    ADD COLUMN stated_gross      NUMERIC,
    -- Header-level variances, comma-separated like the line-level ones: TOTAL_MISMATCH.
    ADD COLUMN header_variances  TEXT NOT NULL DEFAULT '',
    -- When the AP posting was written. Set at capture for every invoice (see above).
    ADD COLUMN posted_at         TIMESTAMPTZ,
    -- The decision on a flagged invoice: who, when and why. APPROVED releases it for payment;
    -- REJECTED reverses its posting and frees the quantities it billed for a corrected invoice.
    ADD COLUMN resolved_at       TIMESTAMPTZ,
    ADD COLUMN resolved_by       UUID,
    ADD COLUMN resolution_reason TEXT;

ALTER TABLE supplier_invoices DROP CONSTRAINT chk_si_status;
ALTER TABLE supplier_invoices
    ADD CONSTRAINT chk_si_status CHECK (status IN ('MATCHED','FLAGGED','APPROVED','REJECTED'));

-- Every invoice captured before this migration was posted by nothing. They are left with
-- posted_at NULL rather than back-posted: a posting dated today for an invoice dated months ago
-- would land in the wrong period, and inventing one is finance's decision, made with a journal.
