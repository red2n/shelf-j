-- Delivery in over a network (readiness review 07.13, the transport seam).
--
-- The inbox took uploads only: a person fetched the supplier's document and posted it. The
-- mandates that make a business receive e-invoices name a network, and the network delivers —
-- a Peppol access point pushes what it received over AS4, France's approved platform what was
-- deposited for the business, and on this platform the simulated provider standing in for a
-- network delivers straight into the receiver's inbox when the receiver is a business here. The
-- channel says which delivered it, and the network's own reference for the delivery is kept beside
-- the document, so a question from the network can be answered by it. KSeF is not here: a Polish
-- buyer pulls its invoices from the system, which is a later part of the seam.
ALTER TABLE supplier_einvoices
    DROP CONSTRAINT chk_supplier_einvoice_channel,
    ADD CONSTRAINT chk_supplier_einvoice_channel
        CHECK (channel IN ('UPLOAD', 'PEPPOL', 'FR_PDP', 'SIMULATED')),
    ADD COLUMN delivery_ref TEXT;   -- the network's reference for the delivery; null for an upload
