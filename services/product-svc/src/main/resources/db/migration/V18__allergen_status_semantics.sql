-- SJ-D42. V17's comment on allergen_status says UNDECLARED is the default "so a new food product is
-- silently unsafe to advertise rather than silently safe". The column beneath that comment defaults
-- to NOT_APPLICABLE, and nothing ever wrote UNDECLARED, so the allergen-gaps list an inspector asks
-- for could never return a row.
--
-- The default is right and the comment was wrong. Most of a supermarket's catalogue is not food,
-- and defaulting everything to UNDECLARED would bury the real gaps under batteries and bin bags. An
-- item becomes UNDECLARED when it is marked as food, which the compliance update now does.
--
-- V17 cannot be edited once applied — Flyway checksums it — so the correction is recorded on the
-- column itself, where the next reader of the schema will find it.
COMMENT ON COLUMN product_variants.allergen_status IS
  'NOT_APPLICABLE (the default): not a food product. UNDECLARED: food whose allergens nobody has declared yet; listed by /admin/products/allergen-gaps and never shown to a shopper as free-from. DECLARED: allergens stated, where an empty declaration means none of the fourteen.';
