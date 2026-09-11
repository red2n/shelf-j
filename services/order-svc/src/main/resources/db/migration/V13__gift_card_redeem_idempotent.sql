-- A POS sale that is captured offline and replayed later re-sends every write in the
-- sale, relying on the idempotency the rest of the flow already has: order placement
-- replays via orders.idempotency_key, and a tender via payment_tenders.idempotency_key.
-- Gift-card redemption had no such key -- redeemGiftCard simply decremented the balance,
-- so a replayed sale (or any retried request whose response was lost) redeemed twice and
-- the customer silently lost the money.
--
-- The natural key is the order the card was redeemed against, matching how payment-svc
-- already makes a STORE_CREDIT tender idempotent ("sc:"+orderId). One card may only be
-- redeemed once per order; redemptions with no order (a manual back-office adjustment)
-- are unconstrained, hence the partial index.
CREATE UNIQUE INDEX uq_gct_redeem_per_order
    ON gift_card_transactions (tenant_id, gift_card_id, order_id)
    WHERE tx_type = 'REDEEM' AND order_id IS NOT NULL;
