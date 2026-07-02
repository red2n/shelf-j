ALTER TABLE cash_movements ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_movement_idempotency
    ON cash_movements (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
