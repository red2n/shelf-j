-- The tender the customer chose at checkout (CASH | CARD | UPI | WALLET). Nullable: POS orders
-- can settle with several split tenders (recorded in payment-svc), and legacy rows predate the
-- column. For ONLINE orders this is the customer's declared intent — e.g. CASH + fulfilment
-- DELIVERY is cash-on-delivery; actual settlement still lives in payment-svc.
ALTER TABLE orders ADD COLUMN payment_method TEXT;
