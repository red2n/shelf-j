-- Category-scoped promotions and mix-and-match (readiness review 03.8).
--
-- A promotion scoped to a CATEGORY was accepted, stored and never applied, because this service
-- had no idea which variants were in which category: product-svc published no catalogue event.
-- It does now — ProductCategorised carries a product's category path (its own category up to the
-- root) and its variant ids, and VariantCreated names the product a new variant belongs to. These
-- two tables are the projection; a category scope resolves to variants through them at quote
-- time, and a promotion scoped to a parent category reaches the products of its children because
-- the whole path is kept.

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalogue_products (
    tenant_id     UUID   NOT NULL,
    product_id    UUID   NOT NULL,
    -- The product's category, then its parent, then the parent's parent, up to the root.
    category_path UUID[] NOT NULL DEFAULT '{}',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product_id)
);
CREATE INDEX idx_catalogue_products_path ON catalogue_products USING GIN (category_path);

CREATE TABLE catalogue_variants (
    tenant_id  UUID NOT NULL,
    variant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, variant_id)
);
CREATE INDEX idx_catalogue_variants_product ON catalogue_variants (tenant_id, product_id);

-- MIX_MATCH: any buy_qty units from the scope for `value` — "any 3 for £10". buy_qty is the
-- bundle size; the other two BOGO quantities do not apply.
ALTER TABLE promotions DROP CONSTRAINT promotions_type_check;
ALTER TABLE promotions ADD CONSTRAINT promotions_type_check CHECK (type IN (
    'PERCENT', 'FLAT', 'BASKET_PERCENT', 'BASKET_FLAT', 'SPEND_THRESHOLD', 'BOGO', 'MIX_MATCH'
));
ALTER TABLE promotions DROP CONSTRAINT promotions_bogo_shape;
ALTER TABLE promotions ADD CONSTRAINT promotions_bogo_shape CHECK (
    (type = 'BOGO' AND buy_qty > 0 AND get_qty > 0
                   AND get_discount_pct > 0 AND get_discount_pct <= 100)
    OR (type = 'MIX_MATCH' AND buy_qty >= 2 AND get_qty IS NULL AND get_discount_pct IS NULL)
    OR (type NOT IN ('BOGO', 'MIX_MATCH') AND buy_qty IS NULL AND get_qty IS NULL
                   AND get_discount_pct IS NULL)
);
