-- Gap #34: manufacturer part number on product_variants.
-- Allows cross-referencing a variant back to the manufacturer's own part number,
-- which differs from sku (internal) and barcode (scan code).
ALTER TABLE product_variants ADD COLUMN manufacturer_pn TEXT;
CREATE INDEX idx_variants_manufacturer_pn ON product_variants (tenant_id, manufacturer_pn)
    WHERE manufacturer_pn IS NOT NULL;
