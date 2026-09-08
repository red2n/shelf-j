-- The staff exception report ("which cashier is an outlier?") reads three append-only
-- logs over a period. Two of them were already indexed for it:
--   order_discounts   (tenant_id, store_id, created_at DESC) and (tenant_id, granted_by, created_at DESC)
--   pos_no_sale_log   (tenant_id, store_id, logged_at DESC)
-- pos_void_log was not. Its only index is (tenant_id, order_id), which answers "was this
-- order voided?" but not "what was voided last month", so the report would sequentially
-- scan every void the tenant has ever recorded.
CREATE INDEX idx_pos_void_tenant_time ON pos_void_log (tenant_id, voided_at DESC);
CREATE INDEX idx_pos_void_tenant_actor ON pos_void_log (tenant_id, voided_by, voided_at DESC)
    WHERE voided_by IS NOT NULL;

-- Same question of the transaction journal, which supplies the report's denominator: a
-- raw count of exceptions ranks staff by how much they worked, not by how they behaved.
CREATE INDEX idx_pos_log_tenant_cashier ON pos_log_entries (tenant_id, cashier_id, transaction_ts DESC)
    WHERE cashier_id IS NOT NULL;
