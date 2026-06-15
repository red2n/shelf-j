package com.shelfj.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/** Request/response DTOs for product-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  public record CreateBrandRequest(@NotBlank String name) {}

  public record UpdateBrandRequest(@NotBlank String name) {}

  public record CreateCategoryRequest(@NotBlank String name, String parentId) {}

  public record UpdateCategoryRequest(@NotBlank String name, String parentId) {}

  public record CreateProductRequest(
      @NotBlank String name,
      String description,
      String brandId,
      String categoryId,
      Boolean sellableOnline,
      Boolean sellablePos) {}

  /** Replace a product's store assortment. Empty/null = sold at all stores. */
  public record ProductStoresRequest(java.util.List<String> storeIds) {}

  public record UpdateProductRequest(
      @NotBlank String name,
      String description,
      String brandId,
      String categoryId,
      @NotNull Boolean sellableOnline,
      @NotNull Boolean sellablePos) {}

  public record CreateVariantRequest(
      @NotBlank String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit) {}

  public record UpdateVariantRequest(
      @NotBlank String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit) {}

  // ── responses ────────────────────────────────────────────────────────────────

  public record BrandResponse(
      String id, String name, String status, String createdAt, String updatedAt) {}

  public record CategoryResponse(
      String id, String parentId, String name, String status, String createdAt, String updatedAt) {}

  public record ProductResponse(
      String id,
      String name,
      String description,
      String brandId,
      String categoryId,
      String status,
      boolean sellableOnline,
      boolean sellablePos,
      String createdAt,
      String updatedAt) {}

  public record VariantResponse(
      String id,
      String productId,
      String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit,
      String status,
      String createdAt,
      String updatedAt) {}

  /** Returned by the POS barcode-scan endpoint — includes product context in one round-trip. */
  public record VariantScanResponse(
      String variantId,
      String productId,
      String productName,
      String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit,
      String status,
      String createdAt,
      String updatedAt) {}

  // ── UOM ──────────────────────────────────────────────────────────────────

  public record UomClassResponse(String code, String name) {}

  public record UomDefinitionResponse(String code, String name, String classCode) {}

  public record UomItemConversionRequest(
      @NotBlank String variantId,
      @NotBlank String fromUom,
      @NotBlank String toUom,
      @NotNull @Positive BigDecimal factor) {}

  public record UomItemConversionResponse(
      String id, String variantId, String fromUom, String toUom, BigDecimal factor) {}

  public record ConvertResult(
      String fromUom,
      String toUom,
      BigDecimal originalQty,
      BigDecimal convertedQty,
      BigDecimal factor,
      String source) {}

  // ── Supplier / Customer Cross-References (Gap #33) ───────────────────────

  public record CreateItemCrossReferenceRequest(
      @NotBlank String partyType,
      @NotBlank String partyId,
      String partyName,
      @NotBlank String crossRefNumber) {}

  public record ItemCrossReferenceResponse(
      String id,
      String variantId,
      String partyType,
      String partyId,
      String partyName,
      String crossRefNumber,
      String createdAt) {}

  // ── Item Relationships (Gap #32) ─────────────────────────────────────────

  public record CreateItemRelationshipRequest(
      @NotBlank String relatedVariantId, @NotBlank String relationshipType) {}

  public record ItemRelationshipResponse(
      String id,
      String variantId,
      String relatedVariantId,
      String relationshipType,
      String createdAt) {}

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  public record CreateItemTemplateRequest(
      @NotBlank String name, String description, String attributes) {}

  public record ItemTemplateResponse(
      String id,
      String name,
      String description,
      String attributes,
      String status,
      String createdAt) {}

  public record ItemTemplateApplicationResponse(
      String id, String variantId, String templateId, String appliedAt) {}

  // ── Item Revisions (Gap #12) ─────────────────────────────────────────────

  public record CreateRevisionRequest(
      @NotBlank String revision, String description, @NotBlank String effectiveDate) {}

  public record ItemRevisionResponse(
      String id,
      String variantId,
      String revision,
      String description,
      String effectiveDate,
      String status,
      String createdAt) {}

  // ── Bulk Import ──────────────────────────────────────────────────────────

  public record ImportCategoryRequest(@NotBlank String name, String parentName) {}

  public record ImportVariantRequest(
      @NotBlank String sku,
      String barcode,
      String manufacturerPn,
      String unit,
      String attributes) {}

  public record ImportProductRequest(
      @NotBlank String name,
      String description,
      String categoryName,
      String brandName,
      Boolean sellableOnline,
      Boolean sellablePos,
      @NotNull List<ImportVariantRequest> variants) {}

  /**
   * {@code mode}: "ADD" (default) creates new products/variants (duplicate SKUs error); "REPLACE"
   * upserts by SKU — reuses the product by (name, category) and replaces any existing variant with
   * the same SKU, so re-importing a sheet overrides rather than duplicates.
   */
  public record BulkImportRequest(
      List<ImportCategoryRequest> categories, List<ImportProductRequest> products, String mode) {}

  public record BulkImportError(String item, String reason) {}

  public record BulkImportResult(
      int categoriesCreated,
      int categoriesSkipped,
      int productsCreated,
      int variantsCreated,
      List<BulkImportError> errors) {}

  // ── Container Types (Gap #37) ───────────────────────────────────────────

  public record CreateContainerTypeRequest(
      @NotBlank String code,
      @NotBlank String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      BigDecimal tareWeightKg,
      Integer maxUnits) {}

  public record UpdateContainerTypeRequest(
      @NotBlank String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      BigDecimal tareWeightKg,
      Integer maxUnits) {}

  public record ContainerTypeResponse(
      String id,
      String code,
      String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      BigDecimal tareWeightKg,
      Integer maxUnits,
      String status,
      String createdAt,
      String updatedAt) {}

  public record CreateVariantContainerLinkRequest(
      @NotBlank String containerTypeId,
      @NotNull @Positive Integer qtyPerContainer,
      Boolean isPrimary) {}

  public record VariantContainerLinkResponse(
      String id,
      String variantId,
      String containerTypeId,
      String containerTypeCode,
      String containerTypeName,
      int qtyPerContainer,
      boolean isPrimary,
      String createdAt) {}

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  public record ItemAttributeGroupFieldResponse(
      String fieldCode, String label, String dataType, boolean required, int sortOrder) {}

  public record ItemAttributeGroupResponse(
      String groupCode,
      String name,
      String description,
      List<ItemAttributeGroupFieldResponse> fields) {}

  public record UpsertVariantAttributeGroupRequest(@NotBlank String values) {}

  public record VariantAttributeGroupValuesResponse(
      String id,
      String variantId,
      String groupCode,
      String values,
      String createdAt,
      String updatedAt) {}

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  public record CreateCatalogGroupRequest(@NotBlank String name, String description) {}

  public record CreateCatalogGroupElementRequest(
      @NotBlank String elementName,
      @NotBlank String dataType,
      boolean required,
      String defaultVal,
      int sortOrder) {}

  public record CatalogGroupElementResponse(
      String id,
      String groupId,
      String elementName,
      String dataType,
      boolean required,
      String defaultVal,
      int sortOrder,
      String createdAt) {}

  public record CatalogGroupResponse(
      String id,
      String name,
      String description,
      String status,
      String createdAt,
      String updatedAt,
      List<CatalogGroupElementResponse> elements) {}

  public record AssignCatalogGroupRequest(@NotBlank String groupId, String elementVals) {}

  public record UpdateCatalogAssignmentRequest(String elementVals) {}

  public record CatalogAssignmentResponse(
      String id,
      String variantId,
      String groupId,
      String elementVals,
      String createdAt,
      String updatedAt) {}

  // ── Gap #39: Category sets ────────────────────────────────────────────────
  public record CreateCategorySetRequest(
      @NotBlank String name,
      String description,
      @NotBlank String purpose,
      String defaultCatId,
      boolean controlled) {}

  public record UpdateCategorySetRequest(
      String name,
      String description,
      String purpose,
      String defaultCatId,
      boolean controlled,
      String status) {}

  public record CategorySetResponse(
      String id,
      String name,
      String description,
      String purpose,
      String defaultCatId,
      boolean controlled,
      String status,
      String createdAt,
      String updatedAt) {}

  public record AddCategorySetMemberRequest(@NotBlank String categoryId) {}

  public record CategorySetMemberResponse(
      String id, String setId, String categoryId, String createdAt) {}

  public record AssignVariantCategorySetRequest(
      @NotBlank String setId, @NotBlank String categoryId) {}

  public record VariantCategorySetAssignmentResponse(
      String id,
      String variantId,
      String setId,
      String categoryId,
      String createdAt,
      String updatedAt) {}
}
