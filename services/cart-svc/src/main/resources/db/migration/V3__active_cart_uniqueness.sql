-- Prevent two concurrent "get or create cart" calls for the same customer/session from each
-- inserting a separate ACTIVE cart (the service layer used to do a plain find-then-insert with
-- no DB-level guard).
CREATE UNIQUE INDEX IF NOT EXISTS uq_carts_active_customer
    ON carts (tenant_id, customer_id)
    WHERE customer_id IS NOT NULL AND status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uq_carts_active_session
    ON carts (tenant_id, session_id)
    WHERE session_id IS NOT NULL AND status = 'ACTIVE';
