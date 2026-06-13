-- Add store_id to payment and refund tenders so Z-reports can aggregate by store
-- without joining to order-svc (database-per-service rule).
-- Existing rows get NULL (back-fill not required — historical data is pre-store-id).

ALTER TABLE payment_tenders
    ADD COLUMN IF NOT EXISTS store_id UUID;

ALTER TABLE refund_tenders
    ADD COLUMN IF NOT EXISTS store_id UUID;

CREATE INDEX IF NOT EXISTS idx_payment_tenders_store
    ON payment_tenders (tenant_id, store_id, created_at DESC)
    WHERE store_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refund_tenders_store
    ON refund_tenders (tenant_id, store_id, created_at DESC)
    WHERE store_id IS NOT NULL;
