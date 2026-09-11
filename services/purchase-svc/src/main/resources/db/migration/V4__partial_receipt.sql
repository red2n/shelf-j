-- Partial receipt (readiness review, horizon 2 item 5).
--
-- A goods receipt closed the whole purchase order regardless of what actually turned up:
--
--   UPDATE purchase_orders SET status='RECEIVED' WHERE ... AND status='SUBMITTED'
--
-- Quantity was never consulted. Two things followed, and the second is the worse one:
--
--   1. A delivery of 6 against an order of 10 marked the order fully received.
--   2. The second delivery of the remaining 4 was then REFUSED — 409 PURCHASE_PO_NOT_SUBMITTED,
--      because the order was no longer SUBMITTED. A split delivery, which is ordinary in retail,
--      stranded the balance with no purchase order left to receive it against. The only way to get
--      that stock on the books was a manual adjustment, which then cites no PO at all.
--
-- The data to do this properly was already there and unread: purchase_order_lines.qty says what
-- was ordered and goods_receipt_lines.qty_received says what came. This migration adds the two
-- states that let the comparison mean something.

ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS po_status;
ALTER TABLE purchase_orders ADD CONSTRAINT po_status CHECK (status IN (
    'DRAFT',
    'SUBMITTED',
    -- Some of it has arrived and more is still expected. Receivable, like SUBMITTED.
    'PARTIALLY_RECEIVED',
    -- Everything ordered has arrived.
    'RECEIVED',
    -- Short-closed: part arrived, the rest never will, and we have stopped waiting. Distinct from
    -- RECEIVED because "we got it all" and "we gave up on the rest" are different facts, and a
    -- supplier scorecard that cannot tell them apart is worthless. Distinct from CANCELLED because
    -- stock IS booked against this order (SJ-D3's reason for refusing to cancel a received one).
    'CLOSED',
    'CANCELLED'
));

-- Why a short-close happened, on the same pattern as the cancellation reason V3 added: a closure
-- whose stated reason is not the one recorded is worse than no reason at all.
ALTER TABLE purchase_orders
    ADD COLUMN closed_at     TIMESTAMPTZ,
    ADD COLUMN closed_reason TEXT;

-- The outstanding-quantity query joins receipt lines to their receipt to reach the PO, and does it
-- inside the receive transaction, so it is on the hot path of every delivery.
CREATE INDEX idx_goods_receipts_po ON goods_receipts (tenant_id, po_id);
CREATE INDEX idx_goods_receipt_lines_gr ON goods_receipt_lines (tenant_id, gr_id, variant_id);

-- Receipt lines are matched to order lines by variant, not by line id — goods_receipt_lines has
-- never carried a po_line_id, and a delivery note names products rather than order rows. Two lines
-- on one order for the same variant therefore aggregate together, which is the same answer a
-- warehouse would give when counting what arrived.
CREATE INDEX idx_purchase_order_lines_variant
    ON purchase_order_lines (tenant_id, po_id, variant_id);
