-- product-svc V2: soft-delete + audit timestamps for brands, categories, variants.
-- Products already had status + updated_at in V1.

ALTER TABLE brands
    ADD COLUMN status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE categories
    ADD COLUMN status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE product_variants
    ADD COLUMN status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Partial indexes support fast "active only" queries without reading deleted rows.
CREATE INDEX idx_brands_tenant_active     ON brands           (tenant_id, name)        WHERE status = 'ACTIVE';
CREATE INDEX idx_categories_tenant_active ON categories       (tenant_id, parent_id)   WHERE status = 'ACTIVE';
CREATE INDEX idx_variants_tenant_active   ON product_variants (tenant_id, product_id)  WHERE status = 'ACTIVE';
