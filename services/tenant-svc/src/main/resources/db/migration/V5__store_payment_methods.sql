-- Per-store tender configuration, owner/admin-controlled. CSV of enabled methods, subset of
-- CASH, CARD, UPI, WALLET. Storefront checkout and POS tender screens offer only these;
-- payment-svc rejects captures with a disabled method.
ALTER TABLE stores ADD COLUMN enabled_payment_methods TEXT NOT NULL DEFAULT 'CASH,CARD';
