-- 03.13: a version on a variant's measure.
--
-- pricing-svc computes every unit price from the measure product-svc announces. Two saves of one
-- variant a moment apart are published in outbox order, and outbox order is not commit order, so
-- the older measure could arrive last and stand: a wrong unit price, silently. Each save now takes
-- the next version under the row's lock and the announcement carries it; pricing-svc keeps a
-- measure only when its version is newer than the one it has.

ALTER TABLE product_variants
    ADD COLUMN measure_version BIGINT NOT NULL DEFAULT 0 CHECK (measure_version >= 0);
