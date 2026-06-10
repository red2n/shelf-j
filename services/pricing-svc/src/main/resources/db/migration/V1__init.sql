-- pricing-svc schema — UK VAT model, price lists, promotions, POSLog tax transactions.
-- All amounts: NUMERIC(18,2). VAT rates: NUMERIC(5,4) (e.g. 0.2000 = 20%).
-- Times: TIMESTAMPTZ UTC. tenant_id on every row — never null.

-- HMRC VAT rate definitions. code: T1=Standard 20%, T5=Reduced 5%, T0=Zero 0%, TX=Exempt.
-- Per HMRC VAT Notice 700. Each tenant configures their own rates (supports multi-jurisdiction).
CREATE TABLE vat_rates (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    code           VARCHAR(8)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    rate           NUMERIC(5,4) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    exempt         BOOLEAN     NOT NULL DEFAULT FALSE,
    description    VARCHAR(255),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vat_rates_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_vat_rates_tenant ON vat_rates (tenant_id);

-- Maps product variants to their HMRC VAT code.
CREATE TABLE product_vat_categories (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    variant_id     UUID        NOT NULL,
    vat_code       VARCHAR(8)  NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_vat_tenant_variant UNIQUE (tenant_id, variant_id)
);
CREATE INDEX idx_product_vat_tenant ON product_vat_categories (tenant_id, variant_id);

-- B2B customer VAT registration. VAT number format: GB + 9 digits (e.g. GB123456789).
CREATE TABLE customer_vat_status (
    id                        UUID        PRIMARY KEY,
    tenant_id                 UUID        NOT NULL,
    customer_id               UUID        NOT NULL,
    vat_number                VARCHAR(20),
    vat_registered            BOOLEAN     NOT NULL DEFAULT FALSE,
    reverse_charge_eligible   BOOLEAN     NOT NULL DEFAULT FALSE,
    country_code              CHAR(2)     NOT NULL DEFAULT 'GB',
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_vat_tenant_customer UNIQUE (tenant_id, customer_id)
);
CREATE INDEX idx_customer_vat_tenant ON customer_vat_status (tenant_id, customer_id);

-- Named price lists per tenant. Default currency GBP (UK clients).
CREATE TABLE price_lists (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    name           VARCHAR(100) NOT NULL,
    channel        VARCHAR(20) NOT NULL DEFAULT 'ALL',
    currency       CHAR(3)     NOT NULL DEFAULT 'GBP',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to   TIMESTAMPTZ,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_lists_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_price_lists_tenant ON price_lists (tenant_id, active);

-- Per-variant prices within a price list. Supports qty-break tiers via min_qty.
CREATE TABLE price_list_items (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    price_list_id  UUID         NOT NULL REFERENCES price_lists (id),
    variant_id     UUID         NOT NULL,
    price          NUMERIC(18,2) NOT NULL CHECK (price >= 0),
    min_qty        NUMERIC(18,4) NOT NULL DEFAULT 1 CHECK (min_qty > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_price_list_items_tenant_list_variant_qty
        UNIQUE (tenant_id, price_list_id, variant_id, min_qty)
);
CREATE INDEX idx_price_list_items_tenant ON price_list_items (tenant_id, price_list_id, variant_id);

-- Time-bounded promotional discounts. type: PERCENT (% off) or FLAT (£ off).
CREATE TABLE promotions (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    store_id         UUID,
    name             VARCHAR(200) NOT NULL,
    type             VARCHAR(20) NOT NULL CHECK (type IN ('PERCENT','FLAT')),
    value            NUMERIC(18,4) NOT NULL CHECK (value > 0),
    min_order_amount NUMERIC(18,2),
    channel          VARCHAR(20) NOT NULL DEFAULT 'ALL',
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promotions_tenant ON promotions (tenant_id, active, starts_at);

-- Scope a promotion to VARIANT, CATEGORY, or ALL.
CREATE TABLE promotion_items (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    promotion_id   UUID        NOT NULL REFERENCES promotions (id),
    scope_type     VARCHAR(20) NOT NULL CHECK (scope_type IN ('VARIANT','CATEGORY','ALL')),
    scope_id       UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promotion_items_promo ON promotion_items (promotion_id);

-- POSLog-compatible tax capture per order line.
-- tax_point_date = time of supply per s.6 VATA 1994. Feeds HMRC MTD boxes 1 and 6.
-- Append-only: no UPDATE or DELETE on this table.
CREATE TABLE tax_transactions (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    order_id       UUID         NOT NULL,
    order_line_id  UUID         NOT NULL,
    variant_id     UUID         NOT NULL,
    store_id       UUID         NOT NULL,
    vat_code       VARCHAR(8)   NOT NULL,
    vat_rate       NUMERIC(5,4) NOT NULL,
    net_amount     NUMERIC(18,2) NOT NULL,
    vat_amount     NUMERIC(18,2) NOT NULL,
    gross_amount   NUMERIC(18,2) NOT NULL,
    exempt         BOOLEAN      NOT NULL DEFAULT FALSE,
    tax_point_date TIMESTAMPTZ  NOT NULL,
    invoice_ref    VARCHAR(50),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tax_transactions_tenant ON tax_transactions (tenant_id, tax_point_date);
CREATE INDEX idx_tax_transactions_order  ON tax_transactions (tenant_id, order_id);

-- Transactional outbox for async event publishing (PriceChanged, PromotionActivated).
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
