-- Gap #4: Material Status — physical condition of a batch (orthogonal to lifecycle status)
-- material_status: AVAILABLE | QUARANTINE | INSPECTION | DAMAGED | RECALLED
-- lifecycle  status: ACTIVE   | DEPLETED   | EXPIRED   (already exists from V2)

ALTER TABLE inventory_batches
    ADD COLUMN material_status          TEXT        NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN material_status_reason   TEXT,
    ADD COLUMN material_status_changed_at TIMESTAMPTZ;

ALTER TABLE inventory_batches
    ADD CONSTRAINT chk_material_status CHECK (
        material_status IN ('AVAILABLE', 'QUARANTINE', 'INSPECTION', 'DAMAGED', 'RECALLED')
    );

-- Partial index for availability queries: only AVAILABLE + ACTIVE batches contribute to stock levels
CREATE INDEX idx_batches_available_material
    ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at)
    WHERE material_status = 'AVAILABLE' AND status = 'ACTIVE';

-- Index for listing batches filtered by material_status
CREATE INDEX idx_batches_material_status
    ON inventory_batches (tenant_id, material_status);
