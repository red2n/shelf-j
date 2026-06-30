ALTER TABLE goods_receipts ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_goods_receipt_idempotency
    ON goods_receipts (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
