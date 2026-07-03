-- One primary image per product, stored in-service as BYTEA (the stack has no object store yet;
-- images are owner-uploaded thumbnails capped at 512 KB by the service). Served with long-lived
-- cache headers from GET /catalog/products/{id}/image.
CREATE TABLE product_images (
    product_id   UUID PRIMARY KEY REFERENCES products(id),
    tenant_id    UUID NOT NULL,
    content_type TEXT NOT NULL,
    bytes        BYTEA NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_images_tenant ON product_images (tenant_id);
