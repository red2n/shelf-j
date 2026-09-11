-- Promotional discounts on an order, from the rebuilt promotion engine.
--
-- Kept apart from orders.discount_amount deliberately. That column is the *staff* discount
-- (SJ-D6): a deliberate act by a named person, requiring a reason, capped by the calling role's
-- percentage ceiling, and audited in order_discounts. A promotional discount is none of those
-- things — it is automatic, has no actor, and answers to a rule rather than to a person. Adding
-- one to the other would put promotional money inside the role-ceiling check, so a large automatic
-- offer would start refusing a cashier's small manual one.
ALTER TABLE orders
    ADD COLUMN promotion_discount NUMERIC(18,2) NOT NULL DEFAULT 0;

-- Which promotions applied, and for how much. Append-only (golden rule #8).
--
-- pricing-svc keeps the redemption ledger that enforces usage caps; this is the order's own
-- record, and it exists for two readers the ledger cannot serve: a receipt that has to print
-- "Summer Sale  -£5.00" beside the line it came off, and a refund that needs to know what was
-- discounted before deciding what to give back.
CREATE TABLE order_promotions (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID          NOT NULL,
    order_id       UUID          NOT NULL REFERENCES orders(id),
    promotion_id   UUID          NOT NULL,
    promotion_name TEXT          NOT NULL,
    -- Null for a whole-basket promotion, which belongs to no single line.
    variant_id     UUID,
    amount         NUMERIC(18,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_promotions_order ON order_promotions (tenant_id, order_id);

-- Reporting reads this by promotion to answer "what did that campaign cost us", which is the
-- question a marketing manager asks the day after it ends.
CREATE INDEX idx_order_promotions_promotion
    ON order_promotions (tenant_id, promotion_id, created_at DESC);
