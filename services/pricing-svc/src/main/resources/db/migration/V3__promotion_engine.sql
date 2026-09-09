-- Rebuild of the promotion engine (readiness review, horizon 2 item 4).
--
-- The engine this replaces could express exactly one thing: a percentage or a flat amount off a
-- single line. Four of its declared capabilities did not work at all, and none of them failed
-- loudly:
--
--   * min_order_amount was written, returned in every API response, and never read — so
--     "£5 off orders over £100" took £5 off a £3 order.
--   * scope_type CATEGORY was in the CHECK constraint, the domain constants, the request DTO and
--     the API guide; the matching query handled only ALL and VARIANT, so a category promotion
--     never fired.
--   * promotions.store_id was stored and never filtered, so a promotion created for one shop ran
--     in every shop of the tenant.
--   * selection was ORDER BY value DESC, which compares a PERCENT's value (15, meaning 15%)
--     against a FLAT's (20, meaning £20) as though they were the same unit.
--
-- The columns below exist so the engine can answer the questions retail actually asks, and the
-- engine reads every one of them. Anything a tenant can configure here changes what it charges.

-- ── promotions: stacking, coupons, and the shapes the old table could not hold ───────────────

ALTER TABLE promotions
    -- Applied in ascending order, so a lower number runs first. Explicit rather than emergent:
    -- the old engine's answer to "which promotion wins" was an accident of a SQL sort.
    ADD COLUMN priority          INTEGER       NOT NULL DEFAULT 100,
    -- An exclusive promotion that applies stops every promotion after it. This is how "20% off
    -- everything, cannot be combined with other offers" is expressed.
    ADD COLUMN exclusive         BOOLEAN       NOT NULL DEFAULT FALSE,
    -- NULL = automatic (applies whenever it matches). Non-null = the customer must present it.
    ADD COLUMN coupon_code       TEXT,
    -- Usage caps. NULL means uncapped; redemption_count is maintained by the redemption ledger.
    ADD COLUMN max_redemptions   INTEGER,
    ADD COLUMN max_per_customer  INTEGER,
    -- BOGO: buy buy_qty of the scoped items, get get_qty at get_discount_pct off (100 = free).
    ADD COLUMN buy_qty           NUMERIC(18,3),
    ADD COLUMN get_qty           NUMERIC(18,3),
    ADD COLUMN get_discount_pct  NUMERIC(5,2);

-- The type vocabulary the engine understands. PERCENT and FLAT keep their existing meaning
-- (per line); the rest are new and could not previously be expressed at all.
ALTER TABLE promotions DROP CONSTRAINT IF EXISTS promotions_type_check;
ALTER TABLE promotions ADD CONSTRAINT promotions_type_check CHECK (type IN (
    'PERCENT',          -- % off each matching line
    'FLAT',             -- fixed amount off each matching unit
    'BASKET_PERCENT',   -- % off the whole basket
    'BASKET_FLAT',      -- fixed amount off the whole basket
    'SPEND_THRESHOLD',  -- fixed amount off once the basket clears min_order_amount
    'BOGO'              -- buy X get Y at a discount
));

-- A BOGO needs its three quantities, and nothing else may carry them: a half-configured BOGO is
-- a promotion that silently discounts nothing, which is the class of bug this migration exists to
-- end. Enforced in the database so it holds however the row was written.
ALTER TABLE promotions ADD CONSTRAINT promotions_bogo_shape CHECK (
    (type = 'BOGO' AND buy_qty > 0 AND get_qty > 0
                   AND get_discount_pct > 0 AND get_discount_pct <= 100)
    OR (type <> 'BOGO' AND buy_qty IS NULL AND get_qty IS NULL AND get_discount_pct IS NULL)
);

-- A threshold promotion without a threshold is just a discount, and would apply to every basket.
ALTER TABLE promotions ADD CONSTRAINT promotions_threshold_shape CHECK (
    type <> 'SPEND_THRESHOLD' OR min_order_amount IS NOT NULL
);

-- A percentage that is not a percentage is the other half of the unit confusion above.
ALTER TABLE promotions ADD CONSTRAINT promotions_percent_range CHECK (
    type NOT IN ('PERCENT','BASKET_PERCENT') OR (value > 0 AND value <= 100)
);

-- Coupon codes are matched case-insensitively — a customer typing SAVE10 must get the promotion
-- created as save10 — so uniqueness has to be case-insensitive too, or two rows could both claim
-- the same code and which one applied would again be an accident of ordering.
CREATE UNIQUE INDEX idx_promotions_coupon
    ON promotions (tenant_id, upper(coupon_code))
    WHERE coupon_code IS NOT NULL;

-- The engine's candidate query filters on tenant, active, window and (since this migration) store.
DROP INDEX IF EXISTS idx_promotions_tenant;
CREATE INDEX idx_promotions_tenant
    ON promotions (tenant_id, active, starts_at, priority);

-- ── redemptions: an append-only ledger, and the only source of a usage count ─────────────────
--
-- Append-only (golden rule #8). A counter column on promotions would have been cheaper to read
-- and impossible to audit: "this coupon is exhausted" is a claim a tenant will dispute, and the
-- answer has to be a list of orders rather than a number.
CREATE TABLE promotion_redemptions (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL,
    promotion_id  UUID          NOT NULL REFERENCES promotions (id),
    order_id      UUID          NOT NULL,
    customer_id   UUID,
    -- What this promotion actually took off that order, so a redemption is worth auditing rather
    -- than merely counting.
    amount        NUMERIC(18,2) NOT NULL,
    currency      TEXT          NOT NULL,
    redeemed_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One redemption per promotion per order. This is the whole idempotency story: a retried
-- checkout, or an offline POS sale replaying its writes, must not burn a second use of a coupon.
-- SJ-D15 was exactly this defect on gift cards, found by asking what a replay would do rather
-- than whether the code was correct.
CREATE UNIQUE INDEX idx_promotion_redemptions_once
    ON promotion_redemptions (tenant_id, promotion_id, order_id);

-- Serves both caps: total usage (tenant + promotion) and per-customer usage.
CREATE INDEX idx_promotion_redemptions_customer
    ON promotion_redemptions (tenant_id, promotion_id, customer_id)
    WHERE customer_id IS NOT NULL;
