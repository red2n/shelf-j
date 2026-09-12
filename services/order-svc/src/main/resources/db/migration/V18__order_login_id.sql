-- SJ-D44: one column was holding two kinds of id.
--
-- A POS sale attaches the shop's own customer record, so customer_id was a customer-svc id. An
-- online checkout stamped the shopper's iam-svc LOGIN id into the same column. The two id spaces
-- never meet, so on live data no order's customer_id resolved to a customer at all — and every
-- customer-keyed path silently skipped every online order: loyalty awarded nothing, the
-- confirmation email found no address, and a GDPR erasure could not reach the delivery name, phone
-- and address the order holds (art.17).
--
-- The login now has a column of its own. customer_id means what it always meant — the shop's record
-- of the buyer — and order-svc fills both at checkout, resolving one from the other through
-- customer-svc (POST /customers/me). Keeping the login on the order as well is deliberate: it costs
-- one column and it is what authorises a shopper to read their own order without a lookup, and what
-- lets an erasure find orders placed before the link existed.
ALTER TABLE orders ADD COLUMN login_id UUID;

CREATE INDEX idx_orders_login ON orders (tenant_id, login_id) WHERE login_id IS NOT NULL;

-- Move the mis-filed ids to the column that means what they are. An ONLINE order's customer_id was
-- never a customer id, so nothing that reads it as one is losing anything: it is being corrected,
-- not cleared. A POS order is untouched — its customer_id was always right.
UPDATE orders
   SET login_id    = customer_id,
       customer_id = NULL
 WHERE channel = 'ONLINE'
   AND customer_id IS NOT NULL;

-- Erasure has to reach a person by either id: the shop erases a customer it knows (customer_id),
-- and that same person's online orders are filed under their login (login_id). Recording both on
-- the erasure lets one sweep find both, including orders placed before this migration.
ALTER TABLE customer_erasures ADD COLUMN login_id UUID;

CREATE INDEX idx_customer_erasures_login
    ON customer_erasures (login_id) WHERE login_id IS NOT NULL;
