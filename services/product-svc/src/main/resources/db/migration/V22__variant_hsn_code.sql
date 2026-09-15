-- An item's HSN or SAC code (readiness review 18.9).
--
-- India's e-invoice (FORM GST INV-01) names every line by its Harmonized System of Nomenclature
-- code, or for a service its Services Accounting Code, and the Invoice Registration Portal refuses
-- a line without one. It is a statement about the item, like its origin, so it sits with the other
-- compliance attributes of the variant. Four, six or eight digits; NULL where nobody has classified
-- the item, which only matters to a business that has to report it.

ALTER TABLE product_variants
    ADD COLUMN hsn_code TEXT,
    ADD CONSTRAINT chk_variant_hsn_code CHECK (hsn_code ~ '^([0-9]{4}|[0-9]{6}|[0-9]{8})$');
