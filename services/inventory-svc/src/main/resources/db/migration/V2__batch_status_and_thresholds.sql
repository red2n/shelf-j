-- inventory-svc V2: batch lifecycle status + reorder threshold index.

ALTER TABLE inventory_batches
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';  -- ACTIVE | DEPLETED | EXPIRED

-- Partial index: only active batches participate in FIFO deduction and level queries.
CREATE INDEX idx_batches_active ON inventory_batches (tenant_id, store_id, variant_id)
    WHERE status = 'ACTIVE';

-- Index for threshold lookups (table existed in V1 but lacked a read-path index).
CREATE INDEX IF NOT EXISTS idx_thresholds_tenant_store
    ON reorder_thresholds (tenant_id, store_id);
