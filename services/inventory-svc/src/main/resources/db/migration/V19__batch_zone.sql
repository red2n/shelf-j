-- Pins a received batch to the physical zone/aisle it sits in within its store.
-- zone_id is owned by tenant-svc (Tenant -> Store -> Zone); referenced here by id only,
-- never joined (database-per-service, golden rule #1).

ALTER TABLE inventory_batches ADD COLUMN zone_id UUID;
CREATE INDEX idx_batches_zone ON inventory_batches (tenant_id, store_id, zone_id) WHERE zone_id IS NOT NULL;
