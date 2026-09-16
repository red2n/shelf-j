-- The drinks container a variant is sold in (readiness review 09.16).
--
-- A deposit return scheme charges a deposit on a drink by what it comes in: the material and the
-- volume of the container (England and Northern Ireland from 1 October 2027, SI 2025/67: PET,
-- steel and aluminium from 150 ml to 3 litres; Germany since 2003, Verpackungsgesetz §31). The
-- amount is the scheme's, held as jurisdiction data in tenant-svc; the catalogue holds only the
-- container, so the same product carries the right deposit in every country it is sold in.
ALTER TABLE product_variants
    ADD COLUMN deposit_material  TEXT,     -- PET, ALUMINIUM, STEEL or GLASS; null when not a drinks container
    ADD COLUMN deposit_volume_ml INTEGER,  -- the container's volume in millilitres
    ADD CONSTRAINT chk_variant_deposit_material
        CHECK (deposit_material IS NULL OR deposit_material IN ('PET', 'ALUMINIUM', 'STEEL', 'GLASS')),
    ADD CONSTRAINT chk_variant_deposit_volume
        CHECK (deposit_volume_ml IS NULL OR (deposit_volume_ml > 0 AND deposit_volume_ml <= 10000)),
    ADD CONSTRAINT chk_variant_deposit_pair
        CHECK ((deposit_material IS NULL) = (deposit_volume_ml IS NULL));
