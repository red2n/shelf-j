-- Where a network's delivery lands (readiness review 07.13, the transport seam).
--
-- An access point delivers an e-invoice to the participant identifier the document names as its
-- buyer (BT-49); France's platform to a directory address, and a document with no address at all
-- names its buyer by VAT identifier (BT-48). purchase-svc asks which business holds what the
-- document names, platform-wide, before anything is read into that business's inbox. The identity
-- columns were written only ever read by their own tenant, so nothing indexed them across tenants.
CREATE INDEX idx_tenants_einvoice_address
    ON tenants (einvoice_scheme, lower(einvoice_id))
    WHERE einvoice_id IS NOT NULL;
CREATE INDEX idx_tenants_vat_number
    ON tenants (upper(replace(vat_number, ' ', '')))
    WHERE vat_number IS NOT NULL;
