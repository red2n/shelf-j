-- 18.4: tamper evidence for the legal receipt register. Every document issued from now on
-- carries a SHA-256 of its own figures and of the document before it in its series, so an
-- altered or re-inserted row no longer matches — the property SAF-T (PT), KassenSichV (DE) and
-- the Italian corrispettivi all ask a register to have, in the one form this repo can give it
-- without certified hardware or a tax-authority signing key. Documents issued before this
-- migration keep NULL: the chain begins at the next number, and the audit says where.
ALTER TABLE fiscal_receipts ADD COLUMN prev_hash TEXT;
ALTER TABLE fiscal_receipts ADD COLUMN hash TEXT;
