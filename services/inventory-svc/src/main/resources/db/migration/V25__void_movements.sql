-- SJ-D40: a paid till sale is now fulfilled at the counter, so it deducts stock, and voiding one
-- puts the stock back. The void is recorded as a RECEIVE movement with ref_type = 'VOID' and
-- ref_id = the order, which keeps it distinguishable from a customer return (ref_type 'RETURN').
--
-- The demand history and dead-stock reports exclude a SALE whose order was voided by looking for
-- that receipt against the same order and variant. Voids are rare next to other receipts, so a
-- partial index keeps the lookup cheap without indexing every movement the tenant has ever made.
CREATE INDEX idx_stock_movements_voids
    ON stock_movements (tenant_id, ref_id, variant_id)
    WHERE ref_type = 'VOID';
