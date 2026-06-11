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

  // ── Gap #33: Supplier / Customer Cross-References ────────────────────────

  public record ItemCrossReference(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String partyType,
      UUID partyId,
      String partyName,
      String crossRefNumber,
      Instant createdAt) {
    public static final String SUPPLIER = "SUPPLIER";
    public static final String CUSTOMER = "CUSTOMER";
  }

  // ── Gap #32: Item Relationships ─────────────────────────────────────────

  public record ItemRelationship(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID relatedVariantId,
      String relationshipType,
      Instant createdAt) {
    public static final String SUBSTITUTE = "SUBSTITUTE";
    public static final String COMPLEMENTARY = "COMPLEMENTARY";
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

  // ── Gap #37: Container Types / Cartonization ────────────────────────────

  public record ContainerType(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record VariantContainerLink(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID containerTypeId,
      int qtyPerContainer,
      boolean isPrimary,
      Instant createdAt) {}

  // ── Gap #36: 18 Oracle Item Attribute Groups ─────────────────────────────

  public record ItemAttributeGroup(String groupCode, String name, String description) {}

  public record ItemAttributeGroupField(
      String groupCode,
      String fieldCode,
      String label,
      String dataType,
      boolean required,
      int sortOrder) {}

  public record VariantAttributeGroupValues(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String groupCode,
      String values,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Gap #35: Item Catalog Groups & Descriptive Elements ──────────────────

  public record CatalogGroup(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record CatalogGroupElement(
      UUID id,
      UUID tenantId,
      UUID groupId,
      String elementName,
      String dataType,
      boolean required,
      String defaultVal,
      int sortOrder,
      Instant createdAt) {
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String TYPE_DATE = "DATE";
  }

  public record VariantCatalogAssignment(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID groupId,
      String elementVals,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Gap #39: Category sets (multi-set / flexfield model) ──────────────────
  public record CategorySet(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String purpose,
      UUID defaultCatId,
      boolean controlled,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String PURPOSE_GENERAL = "GENERAL";
    public static final String PURPOSE_INVENTORY = "INVENTORY";
    public static final String PURPOSE_PURCHASING = "PURCHASING";
    public static final String PURPOSE_COSTING = "COSTING";
    public static final String PURPOSE_SALES = "SALES";
  }

  public record CategorySetMember(
      UUID id, UUID tenantId, UUID setId, UUID categoryId, Instant createdAt) {}

  public record VariantCategorySetAssignment(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID setId,
      UUID categoryId,
      Instant createdAt,
      Instant updatedAt) {}
}
