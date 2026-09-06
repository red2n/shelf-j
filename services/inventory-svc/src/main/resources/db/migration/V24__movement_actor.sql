-- SJ-D4: stock movements record what happened but not who did it.
--
-- Every other actor-bearing trail in the platform names its actor -- price_overrides.overridden_by,
-- pos_void_log.voided_by, order_status_history.changed_by. stock_movements does not, so a negative
-- ADJUST of -50 units is indistinguishable from any other, and shrinkage cannot be attributed to a
-- person. That is the single highest-value audit case in retail and the one this closes.
--
-- Scope note: only ADJUST movements are left unattributable by the absence of an actor. Every other
-- move type already carries ref_type/ref_id pointing at the record that caused it -- an order for
-- SALE, a GRN for RECEIVE, a transfer header for TRANSFER -- and that record names its own actor.
-- ADJUST alone is written with ref_id = NULL (InventoryRepository.adjustTx), so nothing links it
-- back to anyone. actor_id is therefore populated on the adjustment paths and left NULL elsewhere,
-- where NULL means "caused by a system flow, see ref_type/ref_id" rather than "unknown".
ALTER TABLE stock_movements         ADD COLUMN actor_id UUID;
ALTER TABLE stock_movements_archive ADD COLUMN actor_id UUID;

-- "What did this member of staff adjust?" is the question a shrinkage investigation opens with, so
-- it gets an index; partial, because the column is NULL for every system-caused movement.
CREATE INDEX idx_movements_actor ON stock_movements (tenant_id, actor_id, created_at DESC)
    WHERE actor_id IS NOT NULL;

-- reason_code has existed since V17 and has its own reference table with CRUD endpoints
-- (transaction_reason_codes), but nothing ever wrote it onto a movement: adjustTx took a `reason`
-- argument and passed it only to the outbox event, never to the row. The adjustment paths now
-- persist it, so "why" is queryable alongside "who".
