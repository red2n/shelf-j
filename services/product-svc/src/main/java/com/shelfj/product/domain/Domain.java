package com.shelfj.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for the product catalog. */
public final class Domain {

  private Domain() {}

  public record Brand(
      UUID id, UUID tenantId, String name, String status, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  public record Category(
      UUID id,
      UUID tenantId,
      UUID parentId,
      String name,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  public record Product(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      UUID brandId,
      UUID categoryId,
      String status,
      boolean sellableOnline,
      boolean sellablePos,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELISTED = "DELISTED";
  }

  /** UOM class (system reference — no tenant_id). */
  public record UomClass(UUID id, String code, String name) {}

  /** UOM definition within a class (system reference). */
  public record UomDefinition(UUID id, String classCode, String code, String name) {}

  /** Item-level UOM conversion override (tenant + variant scoped). */
  public record UomItemConversion(
      UUID id, UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal factor) {}

  public record Variant(
      UUID id,
      UUID tenantId,
      UUID productId,
      String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  // ── Gap #13: Item Templates ──────────────────────────────────────────────

  public record ItemTemplate(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String attributes,
      String status,
      Instant createdAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record ItemTemplateApplication(
      UUID id, UUID tenantId, UUID variantId, UUID templateId, Instant appliedAt) {}

  // ── Gap #12: Item Revisions ───────────────────────────────────────────────

  public record ItemRevision(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String revision,
      String description,
      LocalDate effectiveDate,
      String status,
      Instant createdAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
  }
}
