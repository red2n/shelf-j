-- The business's own e-invoicing identity (readiness review 07.13 and 18.9).
--
-- An e-invoice names its buyer by VAT identifier (BT-48) and electronic address (BT-49), and its
-- seller the same way (BT-31, BT-34). purchase-svc reads the buyer's to know an invoice it received
-- is addressed to this business and not to another it happens to trade with; order-svc writes the
-- seller's on every invoice it issues, and Peppol refuses an invoice without one (PEPPOL-EN16931-R020).
-- The tenant held its legal name and nothing else a tax authority or an access point asks for.

ALTER TABLE tenants
    ADD COLUMN vat_number      TEXT,   -- prefixed with the issuing country, as EN 16931 requires (BR-CO-09)
    ADD COLUMN einvoice_scheme TEXT,   -- EAS code of the Peppol participant identifier
    ADD COLUMN einvoice_id     TEXT;   -- the identifier within that scheme
