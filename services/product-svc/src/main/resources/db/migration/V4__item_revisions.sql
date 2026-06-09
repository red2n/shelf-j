-- Gap #12: Item Revisions (Oracle Inventory Ch. 5)
-- Tracks design/spec versions of a product variant over time. Append-only.

CREATE TABLE item_revisions (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    variant_id     UUID         NOT NULL REFERENCES product_variants(id),
    revision       TEXT         NOT NULL,
    description    TEXT,
    effective_date DATE         NOT NULL,
    status         TEXT         NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_item_revisions   PRIMARY KEY (id),
    CONSTRAINT uq_item_rev_variant UNIQUE (tenant_id, variant_id, revision),
    CONSTRAINT chk_item_rev_status CHECK (status IN ('ACTIVE','SUPERSEDED'))
);

CREATE INDEX idx_item_rev_tenant_variant
    ON item_revisions (tenant_id, variant_id, effective_date DESC);
