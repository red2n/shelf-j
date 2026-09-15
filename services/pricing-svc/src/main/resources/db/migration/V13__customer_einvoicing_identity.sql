-- The buyer's side of a business-to-business invoice (readiness review 18.9).
--
-- An EN 16931 invoice names its buyer by the name the buyer is registered under (BT-44), its VAT
-- identifier (BT-48) and, for delivery over Peppol, its electronic address (BT-49). This table
-- already held the VAT number and whether the customer is registered; a customer record holds a
-- person's first and last name, which is not who a company's invoice is addressed to. For an
-- Indian buyer the VAT number is its GSTIN.

ALTER TABLE customer_vat_status
    ADD COLUMN legal_name      TEXT,   -- as registered for VAT; the invoice's buyer name
    ADD COLUMN einvoice_scheme TEXT,   -- EAS code of the buyer's Peppol participant identifier
    ADD COLUMN einvoice_id     TEXT;   -- the identifier within that scheme
