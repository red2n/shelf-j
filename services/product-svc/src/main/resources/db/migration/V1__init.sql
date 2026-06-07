-- product-svc schema: catalog (products, variants, categories tree, brands, media). README §9.3.
-- Every tenant table: tenant_id NOT NULL + composite index starting tenant_id.

CREATE TABLE brands (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_brands_tenant ON brands (tenant_id, name);

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    parent_id  UUID REFERENCES categories(id),   -- tree; NULL = root
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_categories_tenant ON categories (tenant_id, parent_id);

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    name            TEXT NOT NULL,
    description     TEXT,
    brand_id        UUID REFERENCES brands(id),
    category_id     UUID REFERENCES categories(id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE | DELISTED
    sellable_online BOOLEAN NOT NULL DEFAULT true,
    sellable_pos    BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_tenant ON products (tenant_id, status, created_at DESC);
CREATE INDEX idx_products_tenant_category ON products (tenant_id, category_id);

CREATE TABLE product_variants (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id),
    sku        TEXT NOT NULL,
    barcode    TEXT,
    attributes TEXT,                                    -- JSON string (e.g. {"size":"500g"})
    unit       TEXT,                                    -- e.g. EACH, KG, L
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, sku)
);
CREATE INDEX idx_variants_tenant_product ON product_variants (tenant_id, product_id);
CREATE UNIQUE INDEX uq_variants_tenant_barcode ON product_variants (tenant_id, barcode) WHERE barcode IS NOT NULL;

CREATE TABLE product_media (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id),
    url        TEXT NOT NULL,
    position   INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_tenant_product ON product_media (tenant_id, product_id, position);

-- Transactional outbox.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
