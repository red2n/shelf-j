-- A retried POST /admin/inventory/receive (e.g. a client timeout retry) must not double-count
-- stock. Mirrors the orders.idempotency_key pattern: nullable column, unique only when present
-- (event-driven receives via GoodsReceivedHandler.receiveOnce use their own dedupe and never set
-- this column).
ALTER TABLE inventory_batches ADD COLUMN idempotency_key TEXT;
CREATE UNIQUE INDEX idx_batches_idem ON inventory_batches (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
