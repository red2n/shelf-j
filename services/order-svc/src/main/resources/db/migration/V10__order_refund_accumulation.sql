-- Refunds are now driven by payment-svc's PaymentRefunded event (manual refund or automatic
-- refund on return/cancel). order-svc accumulates the refunded total against the order total to
-- decide REFUNDED vs PARTIALLY_REFUNDED — mirroring how paid_amount accumulates captures (V7).
ALTER TABLE orders ADD COLUMN refunded_amount NUMERIC(18,2) NOT NULL DEFAULT 0;
