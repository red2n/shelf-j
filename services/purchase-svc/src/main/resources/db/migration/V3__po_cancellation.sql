-- SJ-D3: CANCELLED was a declared purchase-order state with no way to reach it.
--
-- V1 declared four states in the CHECK constraint and Domain.java declared four constants, but the
-- only transitions ever written were DRAFT -> SUBMITTED (PurchaseService.submitPurchaseOrder) and
-- SUBMITTED -> RECEIVED (PurchaseRepository.createGoodsReceipt, as a SQL literal). Nothing ever
-- wrote CANCELLED, so a purchase order raised in error was stuck in DRAFT or SUBMITTED forever --
-- and a stuck SUBMITTED order stays receivable indefinitely.
--
-- The cancellation reason is recorded on the order itself rather than in a separate history table:
-- a purchase order is cancelled at most once (the transition is guarded to non-terminal states), so
-- there is no history to keep, and every read path that shows the status already loads this row.
ALTER TABLE purchase_orders ADD COLUMN cancelled_at     TIMESTAMPTZ;
ALTER TABLE purchase_orders ADD COLUMN cancelled_reason TEXT;

-- A cancelled order must carry its reason and timestamp, and a live order must carry neither --
-- otherwise a stale reason from a future un-cancel path could survive on an active order.
ALTER TABLE purchase_orders ADD CONSTRAINT po_cancelled_fields CHECK (
  (status =  'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_reason IS NOT NULL)
  OR
  (status <> 'CANCELLED' AND cancelled_at IS NULL     AND cancelled_reason IS NULL)
);
