-- ── Business audit trail (20.11) ─────────────────────────────────────────────
--
-- The append-only logs this service already writes -- discounts, voids, no-sales, cancellations,
-- returns -- read back as one stream naming who did what. Nothing new is written; this migration
-- gives the read what it lacked.

-- A return recorded the reason and the money but never who took the goods back, while every
-- other sensitive action named its actor. Nullable: rows from before this migration have nobody
-- to name, and the trail shows them as unattributed rather than inventing one.
ALTER TABLE returns ADD COLUMN created_by UUID;

-- The stream is read newest-first per tenant, then narrowed by store, actor or period. Each log
-- already carried the exception report's grouping index; these are the time-ordered reads the
-- trail makes that had no index to answer them.
CREATE INDEX idx_returns_tenant_time ON returns (tenant_id, created_at DESC);
CREATE INDEX idx_order_discounts_tenant_time ON order_discounts (tenant_id, created_at DESC);
CREATE INDEX idx_no_sale_log_tenant_time ON pos_no_sale_log (tenant_id, logged_at DESC);
CREATE INDEX idx_order_history_cancelled ON order_status_history (tenant_id, changed_at DESC)
    WHERE to_status = 'CANCELLED';
