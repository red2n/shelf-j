-- SJ-D35: a customer order can be part-fulfilled — four of five lines, or four of five units.
-- Each line keeps a cumulative handed-over quantity; the order is PARTIALLY_FULFILLED until every
-- line is complete, then FULFILLED as before. Returns and void restocks are capped by what was
-- actually handed over, not by what was ordered.
ALTER TABLE order_items ADD COLUMN fulfilled_qty NUMERIC(18,3) NOT NULL DEFAULT 0;

-- Everything handed over before this migration was handed over whole: the status history says so
-- even for orders that have since been refunded or voided.
UPDATE order_items oi
   SET fulfilled_qty = oi.qty
 WHERE EXISTS (SELECT 1 FROM order_status_history h
                WHERE h.tenant_id = oi.tenant_id AND h.order_id = oi.order_id
                  AND h.to_status = 'FULFILLED');
