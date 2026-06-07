package com.shelfj.product.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain records for the product catalog. */
public final class Domain {

    private Domain() {}

    public record Brand(UUID id, UUID tenantId, String name, Instant createdAt) {}

    public record Category(UUID id, UUID tenantId, UUID parentId, String name, Instant createdAt) {}

    public record Product(
            UUID id, UUID tenantId, String name, String description,
            UUID brandId, UUID categoryId, String status,
            boolean sellableOnline, boolean sellablePos,
            Instant createdAt, Instant updatedAt) {
        public static final String STATUS_ACTIVE = "ACTIVE";
        public static final String STATUS_DELISTED = "DELISTED";
    }

    public record Variant(
            UUID id, UUID tenantId, UUID productId, String sku, String barcode,
            String attributes, String unit, Instant createdAt) {}
}
