-- 03.13: unit prices.
--
-- The Price Marking Order 2004 as amended (from 6 April 2026) and Directive 98/6/EC art.3 require a
-- unit price beside a selling price: per kilogram, litre, metre, square metre, or per item for
-- goods sold by number, for the price the shopper is actually charged, promotional prices included.
-- product-svc knows how a variant is sold and announces it as VariantMeasured: the standard unit
-- and the quantity one price buys. This projection keeps it beside the variant, so every quote can
-- divide the price by it. No measure means none was declared, and the quote says so instead of
-- showing a wrong unit price.

ALTER TABLE catalogue_variants
    ADD COLUMN sold_by          TEXT,
    ADD COLUMN measure_unit     TEXT CHECK (measure_unit IN ('KG', 'L', 'M', 'SQM', 'EA')),
    ADD COLUMN measure_quantity NUMERIC(18, 6) CHECK (measure_quantity > 0),
    ADD COLUMN measured_at      TIMESTAMPTZ,
    -- product-svc's version of the measure: an older one arriving late never replaces a newer.
    ADD COLUMN measure_version  BIGINT CHECK (measure_version >= 0),
    ADD CONSTRAINT chk_catalogue_variant_measure_pair
        CHECK ((measure_unit IS NULL) = (measure_quantity IS NULL));
