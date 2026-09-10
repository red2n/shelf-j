package com.shelfj.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for product-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "CreateBrandRequest")
  public record CreateBrandRequest(@NotBlank String name) {}

  @Schema(name = "UpdateBrandRequest")
  public record UpdateBrandRequest(@NotBlank String name) {}

  @Schema(name = "CreateCategoryRequest")
  public record CreateCategoryRequest(
      @NotBlank String name,
      @Schema(description = "UUID of the parent category, or null for a top-level category.")
          String parentId) {}

  @Schema(name = "UpdateCategoryRequest")
  public record UpdateCategoryRequest(
      @NotBlank String name,
      @Schema(description = "UUID of the parent category, or null for a top-level category.")
          String parentId) {}

  @Schema(name = "CreateProductRequest")
  public record CreateProductRequest(
      @NotBlank String name,
      String description,
      @Schema(description = "UUID of the brand, or null for unbranded.") String brandId,
      @Schema(description = "UUID of the category, or null for uncategorized.") String categoryId,
      @Schema(description = "Whether the storefront shows this product. Defaults to true.")
          Boolean sellableOnline,
      @Schema(description = "Whether POS can sell this product. Defaults to true.")
          Boolean sellablePos) {}

  /** Replace a product's store assortment. Empty/null = sold at all stores. */
  @Schema(
      name = "ProductStoresRequest",
      description = "Store ids this product is restricted to. Empty/null means sold at all stores.")
  public record ProductStoresRequest(
      @Schema(description = "UUIDs of the stores this product is sold at.")
          java.util.List<String> storeIds) {}

  @Schema(name = "UpdateProductRequest")
  public record UpdateProductRequest(
      @NotBlank String name,
      String description,
      @Schema(description = "UUID of the brand, or null for unbranded.") String brandId,
      @Schema(description = "UUID of the category, or null for uncategorized.") String categoryId,
      @Schema(description = "Whether the storefront shows this product.") @NotNull
          Boolean sellableOnline,
      @Schema(description = "Whether POS can sell this product.") @NotNull Boolean sellablePos) {}

  // ── Food safety, origin, age restriction and selling by weight ─────────────

  @Schema(
      name = "AllergenResponse",
      description = "One of the fourteen allergens EU 1169/2011 names.")
  public record AllergenResponse(String code, String name, String detail, String regulation) {}

  @Schema(
      name = "AllergenDeclarationRequest",
      description =
          "A variant's complete allergen declaration. Sending an empty list is how a product is"
              + " declared free from all fourteen — it is a positive statement, not an omission.")
  public record AllergenDeclarationRequest(
      @Schema(
              description =
                  "Every allergen present or possibly present. Replaces the current declaration"
                      + " entirely, so removing an entry is how a mistake is corrected.")
          @NotNull
          @Valid
          List<AllergenEntry> allergens) {}

  @Schema(name = "AllergenEntry")
  public record AllergenEntry(
      @Schema(description = "Allergen code from GET /catalog/allergens.") @NotBlank String code,
      @Schema(
              description =
                  "CONTAINS or MAY_CONTAIN. MAY_CONTAIN is a cross-contamination warning.")
          @NotBlank
          String presence) {}

  @Schema(name = "AllergenDeclarationResponse")
  public record AllergenDeclarationResponse(
      String variantId,
      @Schema(
              description =
                  "UNDECLARED, DECLARED or NOT_APPLICABLE. An empty list with UNDECLARED means"
                      + " nobody has checked — it does NOT mean free from.")
          String status,
      List<AllergenEntry> allergens,
      String declaredAt) {}

  @Schema(
      name = "VariantComplianceRequest",
      description = "Origin, age restriction, and how the item is sold.")
  public record VariantComplianceRequest(
      @Schema(description = "ISO 3166-1 alpha-2, e.g. GB. Uppercased on the way in.")
          String countryOfOrigin,
      @Schema(
              description =
                  "Free text where one code cannot say it: 'Produce of Spain, packed in the UK'.")
          String originDetail,
      @Schema(description = "ALCOHOL, TOBACCO, KNIVES … or null when unrestricted.")
          String restrictionCategory,
      @Schema(description = "Ingredients as printed on the pack.") String ingredients,
      @Schema(description = "EACH, WEIGHT, VOLUME or LENGTH. Defaults to EACH.") String soldBy,
      @Schema(description = "Net quantity in the pack, for the unit price a shelf edge must show.")
          BigDecimal netContent,
      @Schema(description = "UOM code for netContent, e.g. KG, L.") String netContentUom,
      @Schema(description = "Packaging weight a scale deducts before pricing.")
          BigDecimal tareWeight,
      @Schema(
              description =
                  "True when each item has its own weight — a joint of meat, a whole fish.")
          Boolean catchWeight) {}

  @Schema(name = "VariantComplianceResponse")
  public record VariantComplianceResponse(
      String variantId,
      String countryOfOrigin,
      String originDetail,
      String restrictionCategory,
      String allergenStatus,
      String ingredients,
      String soldBy,
      BigDecimal netContent,
      String netContentUom,
      BigDecimal tareWeight,
      boolean catchWeight) {}

  @Schema(
      name = "AgeCheckResponse",
      description = "What the till must ask before selling this item in this country.")
  public record AgeCheckResponse(
      String variantId,
      String country,
      @Schema(
              description =
                  "Whether this item is age-restricted here. Read THIS, not the absence of"
                      + " minimumAge: a null field is omitted from the JSON entirely, so a client"
                      + " that infers 'no age given, therefore sell it' cannot tell an"
                      + " unrestricted item from a field that went missing. This boolean is always"
                      + " present, and its absence is a parse failure rather than a sale.")
          boolean restricted,
      @Schema(description = "Null when the item is not age-restricted.") String category,
      @Schema(description = "Null when the item is not age-restricted.") Integer minimumAge,
      @Schema(description = "True when the rule came from the tenant rather than the statute.")
          boolean tenantOverride) {}

  @Schema(name = "AgeRestrictionRuleResponse")
  public record AgeRestrictionRuleResponse(
      String country,
      String category,
      int minimumAge,
      String note,
      @Schema(description = "True when this tenant set it, false when it is the statutory default.")
          boolean tenantOverride) {}

  @Schema(name = "SetAgeRestrictionRuleRequest")
  public record SetAgeRestrictionRuleRequest(
      @Schema(description = "ISO 3166-1 alpha-2 country the store is in.") @NotBlank String country,
      @NotBlank String category,
      @Schema(
              description =
                  "Must be at or above the statutory minimum — a business may be stricter, never laxer.")
          @NotNull
          Integer minimumAge,
      @Schema(description = "Why this differs from the statutory default.") String reason) {}

  @Schema(name = "CreateVariantRequest")
  public record CreateVariantRequest(
      @Schema(description = "Stock-keeping unit code, unique within the tenant.") @NotBlank
          String sku,
      @Schema(description = "Scannable barcode (e.g. UPC/EAN).") String barcode,
      String manufacturerPn,
      @Schema(description = "Free-form JSON attribute payload.") String attributes,
      @Schema(description = "Base unit of measure code, e.g. EA, CS.") String unit) {}

  @Schema(name = "UpdateVariantRequest")
  public record UpdateVariantRequest(
      @Schema(description = "Stock-keeping unit code, unique within the tenant.") @NotBlank
          String sku,
      @Schema(description = "Scannable barcode (e.g. UPC/EAN).") String barcode,
      String manufacturerPn,
      @Schema(description = "Free-form JSON attribute payload.") String attributes,
      @Schema(description = "Base unit of measure code, e.g. EA, CS.") String unit) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "BrandResponse")
  public record BrandResponse(
      String id, String name, String status, String createdAt, String updatedAt) {}

  @Schema(name = "CategoryResponse")
  public record CategoryResponse(
      String id,
      @Schema(description = "UUID of the parent category, or null for a top-level category.")
          String parentId,
      String name,
      String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "ProductResponse")
  public record ProductResponse(
      String id,
      String name,
      String description,
      @Schema(description = "UUID of the brand, or null for unbranded.") String brandId,
      @Schema(description = "UUID of the category, or null for uncategorized.") String categoryId,
      @Schema(description = "ACTIVE or DELISTED.") String status,
      @Schema(description = "Whether the storefront shows this product.") boolean sellableOnline,
      @Schema(description = "Whether POS can sell this product.") boolean sellablePos,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "VariantResponse")
  public record VariantResponse(
      String id,
      String productId,
      @Schema(description = "Stock-keeping unit code, unique within the tenant.") String sku,
      @Schema(description = "Scannable barcode (e.g. UPC/EAN).") String barcode,
      String manufacturerPn,
      @Schema(description = "Free-form JSON attribute payload.") String attributes,
      @Schema(description = "Base unit of measure code, e.g. EA, CS.") String unit,
      @Schema(description = "ACTIVE or DELISTED.") String status,
      String createdAt,
      String updatedAt) {}

  /** Returned by the POS barcode-scan endpoint — includes product context in one round-trip. */
  @Schema(
      name = "VariantScanResponse",
      description = "A variant plus its parent product context, for a single-round-trip lookup.")
  public record VariantScanResponse(
      String variantId,
      String productId,
      String productName,
      @Schema(description = "Stock-keeping unit code, unique within the tenant.") String sku,
      @Schema(description = "Scannable barcode (e.g. UPC/EAN).") String barcode,
      String manufacturerPn,
      @Schema(description = "Free-form JSON attribute payload.") String attributes,
      @Schema(description = "Base unit of measure code, e.g. EA, CS.") String unit,
      @Schema(description = "ACTIVE or DELISTED.") String status,
      String createdAt,
      String updatedAt) {}

  // ── UOM ──────────────────────────────────────────────────────────────────

  @Schema(name = "UomClassResponse")
  public record UomClassResponse(String code, String name) {}

  @Schema(name = "UomDefinitionResponse")
  public record UomDefinitionResponse(String code, String name, String classCode) {}

  @Schema(name = "UomItemConversionRequest")
  public record UomItemConversionRequest(
      @Schema(description = "UUID of the variant this conversion factor applies to.") @NotBlank
          String variantId,
      @NotBlank String fromUom,
      @NotBlank String toUom,
      @Schema(description = "Multiply a fromUom quantity by this to get toUom.") @NotNull @Positive
          BigDecimal factor) {}

  @Schema(name = "UomItemConversionResponse")
  public record UomItemConversionResponse(
      String id,
      @Schema(description = "UUID of the variant this conversion factor applies to.")
          String variantId,
      String fromUom,
      String toUom,
      @Schema(description = "Multiply a fromUom quantity by this to get toUom.")
          BigDecimal factor) {}

  @Schema(name = "ConvertResult")
  public record ConvertResult(
      String fromUom,
      String toUom,
      BigDecimal originalQty,
      BigDecimal convertedQty,
      @Schema(description = "The conversion factor applied.") BigDecimal factor,
      @Schema(
              description =
                  "IDENTITY (same unit), ITEM (variant-specific), or STANDARD (class-wide).")
          String source) {}

  // ── Supplier / Customer Cross-References (Gap #33) ───────────────────────

  @Schema(name = "CreateItemCrossReferenceRequest")
  public record CreateItemCrossReferenceRequest(
      @Schema(description = "SUPPLIER or CUSTOMER.") @NotBlank String partyType,
      @Schema(description = "UUID of the supplier or customer party.") @NotBlank String partyId,
      String partyName,
      @Schema(description = "The party's own part/item number for this variant.") @NotBlank
          String crossRefNumber) {}

  @Schema(name = "ItemCrossReferenceResponse")
  public record ItemCrossReferenceResponse(
      String id,
      String variantId,
      @Schema(description = "SUPPLIER or CUSTOMER.") String partyType,
      @Schema(description = "UUID of the supplier or customer party.") String partyId,
      String partyName,
      @Schema(description = "The party's own part/item number for this variant.")
          String crossRefNumber,
      String createdAt) {}

  // ── Item Relationships (Gap #32) ─────────────────────────────────────────

  @Schema(name = "CreateItemRelationshipRequest")
  public record CreateItemRelationshipRequest(
      @Schema(description = "UUID of the related variant.") @NotBlank String relatedVariantId,
      @Schema(description = "SUBSTITUTE or COMPLEMENTARY.") @NotBlank String relationshipType) {}

  @Schema(name = "ItemRelationshipResponse")
  public record ItemRelationshipResponse(
      String id,
      String variantId,
      @Schema(description = "UUID of the related variant.") String relatedVariantId,
      @Schema(description = "SUBSTITUTE or COMPLEMENTARY.") String relationshipType,
      String createdAt) {}

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  @Schema(name = "CreateItemTemplateRequest")
  public record CreateItemTemplateRequest(
      @NotBlank String name,
      String description,
      @Schema(description = "Free-form JSON attribute payload applied to variants.")
          String attributes) {}

  @Schema(name = "ItemTemplateResponse")
  public record ItemTemplateResponse(
      String id,
      String name,
      String description,
      @Schema(description = "Free-form JSON attribute payload applied to variants.")
          String attributes,
      String status,
      String createdAt) {}

  @Schema(name = "ItemTemplateApplicationResponse")
  public record ItemTemplateApplicationResponse(
      String id, String variantId, String templateId, String appliedAt) {}

  // ── Item Revisions (Gap #12) ─────────────────────────────────────────────

  @Schema(name = "CreateRevisionRequest")
  public record CreateRevisionRequest(
      @Schema(description = "Revision label, e.g. \"A\", \"2\".") @NotBlank String revision,
      String description,
      @Schema(description = "ISO-8601 date this revision takes effect.") @NotBlank
          String effectiveDate) {}

  @Schema(name = "ItemRevisionResponse")
  public record ItemRevisionResponse(
      String id,
      String variantId,
      @Schema(description = "Revision label, e.g. \"A\", \"2\".") String revision,
      String description,
      @Schema(description = "ISO-8601 date this revision takes effect.") String effectiveDate,
      String status,
      String createdAt) {}

  // ── Bulk Import ──────────────────────────────────────────────────────────

  @Schema(name = "ImportCategoryRequest")
  public record ImportCategoryRequest(
      @NotBlank String name,
      @Schema(description = "Name of the parent category, resolved/created within the same import.")
          String parentName) {}

  @Schema(name = "ImportVariantRequest")
  public record ImportVariantRequest(
      @Schema(description = "Stock-keeping unit code, unique within the tenant.") @NotBlank
          String sku,
      @Schema(description = "Scannable barcode (e.g. UPC/EAN).") String barcode,
      String manufacturerPn,
      @Schema(description = "Base unit of measure code, e.g. EA, CS.") String unit,
      @Schema(description = "Free-form JSON attribute payload.") String attributes) {}

  @Schema(name = "ImportProductRequest")
  public record ImportProductRequest(
      @NotBlank String name,
      String description,
      @Schema(description = "Name of the category, resolved/created within the same import.")
          String categoryName,
      @Schema(description = "Name of the brand, resolved/created within the same import.")
          String brandName,
      Boolean sellableOnline,
      Boolean sellablePos,
      @Schema(description = "UUIDs of the stores this product is assorted to.")
          List<String> storeIds,
      @NotNull @Valid List<ImportVariantRequest> variants) {}

  /**
   * {@code mode}: "ADD" (default) creates new products/variants (duplicate SKUs error); "REPLACE"
   * upserts by SKU — reuses the product by (name, category) and replaces any existing variant with
   * the same SKU, so re-importing a sheet overrides rather than duplicates.
   */
  @Schema(name = "BulkImportRequest")
  public record BulkImportRequest(
      @Valid List<ImportCategoryRequest> categories,
      @Valid List<ImportProductRequest> products,
      @Schema(description = "ADD (default, duplicate SKUs error) or REPLACE (upsert by SKU).")
          String mode) {}

  /**
   * Wraps a raw supplier CSV with optional store-name-to-UUID mapping and import mode. The server
   * parses the CSV; the client resolves store names to UUIDs before sending (since tenant-svc owns
   * store data and product-svc must not call it synchronously during a bulk import).
   *
   * @param storeId destination store UUID for stock receipt (optional — if absent no stock is
   *     received)
   * @param currency ISO-4217 currency for price list creation (defaults to GBP when absent)
   */
  @Schema(name = "SupplierCsvImportRequest")
  public record SupplierCsvImportRequest(
      @Schema(description = "Raw CSV text (GTBJ supplier format).") @NotBlank String csv,
      @Schema(description = "ADD (default) or REPLACE.") String mode,
      @Schema(description = "Maps a CSV store name/column value to a tenant-svc store UUID.")
          java.util.Map<String, String> storeNameToId,
      @Schema(
              description =
                  "Destination store UUID for stock receipt; if absent no stock is received.")
          String storeId,
      @Schema(description = "ISO-4217 currency for price-list creation. Defaults to GBP.")
          String currency) {}

  @Schema(name = "BulkImportError")
  public record BulkImportError(String item, String reason) {}

  /**
   * One successfully created/replaced variant — lets the caller follow up per row (e.g. set initial
   * stock or price) without having to re-look-up variants by SKU afterward.
   */
  @Schema(name = "ImportedVariant")
  public record ImportedVariant(String sku, String variantId, String productId) {}

  @Schema(name = "BulkImportResult")
  public record BulkImportResult(
      int categoriesCreated,
      int categoriesSkipped,
      int productsCreated,
      int variantsCreated,
      List<BulkImportError> errors,
      List<ImportedVariant> importedVariants,
      @Schema(description = "Units received into stock via inventory-svc, if a storeId was given.")
          Integer stockReceived,
      List<String> stockErrors,
      @Schema(description = "Prices upserted via pricing-svc.") Integer pricesSet,
      List<String> priceErrors) {}

  // ── Container Types (Gap #37) ───────────────────────────────────────────

  @Schema(name = "CreateContainerTypeRequest")
  public record CreateContainerTypeRequest(
      @Schema(description = "Short unique code, e.g. \"PLT-STD\".") @NotBlank String code,
      @NotBlank String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      @Schema(description = "Weight of the empty container itself.") BigDecimal tareWeightKg,
      @Schema(description = "Maximum number of units the container can hold.") Integer maxUnits) {}

  @Schema(name = "UpdateContainerTypeRequest")
  public record UpdateContainerTypeRequest(
      @NotBlank String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      @Schema(description = "Weight of the empty container itself.") BigDecimal tareWeightKg,
      @Schema(description = "Maximum number of units the container can hold.") Integer maxUnits) {}

  @Schema(name = "ContainerTypeResponse")
  public record ContainerTypeResponse(
      String id,
      @Schema(description = "Short unique code, e.g. \"PLT-STD\".") String code,
      String name,
      String description,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      BigDecimal maxWeightKg,
      @Schema(description = "Weight of the empty container itself.") BigDecimal tareWeightKg,
      @Schema(description = "Maximum number of units the container can hold.") Integer maxUnits,
      String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "CreateVariantContainerLinkRequest")
  public record CreateVariantContainerLinkRequest(
      @Schema(description = "UUID of the container type.") @NotBlank String containerTypeId,
      @Schema(description = "How many units of the variant fit per container.") @NotNull @Positive
          Integer qtyPerContainer,
      @Schema(description = "Whether this is the variant's primary/default container.")
          Boolean isPrimary) {}

  @Schema(name = "VariantContainerLinkResponse")
  public record VariantContainerLinkResponse(
      String id,
      String variantId,
      String containerTypeId,
      String containerTypeCode,
      String containerTypeName,
      @Schema(description = "How many units of the variant fit per container.") int qtyPerContainer,
      @Schema(description = "Whether this is the variant's primary/default container.")
          boolean isPrimary,
      String createdAt) {}

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  @Schema(name = "ItemAttributeGroupFieldResponse")
  public record ItemAttributeGroupFieldResponse(
      String fieldCode,
      String label,
      @Schema(description = "TEXT, NUMBER, BOOLEAN, or DATE.") String dataType,
      boolean required,
      int sortOrder) {}

  @Schema(name = "ItemAttributeGroupResponse")
  public record ItemAttributeGroupResponse(
      @Schema(description = "Unique code identifying this attribute group.") String groupCode,
      String name,
      String description,
      List<ItemAttributeGroupFieldResponse> fields) {}

  @Schema(name = "UpsertVariantAttributeGroupRequest")
  public record UpsertVariantAttributeGroupRequest(
      @Schema(description = "Free-form JSON payload of field values for this group.") @NotBlank
          String values) {}

  @Schema(name = "VariantAttributeGroupValuesResponse")
  public record VariantAttributeGroupValuesResponse(
      String id,
      String variantId,
      @Schema(description = "Unique code identifying this attribute group.") String groupCode,
      @Schema(description = "Free-form JSON payload of field values for this group.") String values,
      String createdAt,
      String updatedAt) {}

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  @Schema(name = "CreateCatalogGroupRequest")
  public record CreateCatalogGroupRequest(@NotBlank String name, String description) {}

  @Schema(name = "CreateCatalogGroupElementRequest")
  public record CreateCatalogGroupElementRequest(
      @NotBlank String elementName,
      @Schema(description = "TEXT, NUMBER, BOOLEAN, or DATE.") @NotBlank String dataType,
      boolean required,
      String defaultVal,
      int sortOrder) {}

  @Schema(name = "CatalogGroupElementResponse")
  public record CatalogGroupElementResponse(
      String id,
      String groupId,
      String elementName,
      @Schema(description = "TEXT, NUMBER, BOOLEAN, or DATE.") String dataType,
      boolean required,
      String defaultVal,
      int sortOrder,
      String createdAt) {}

  @Schema(name = "CatalogGroupResponse")
  public record CatalogGroupResponse(
      String id,
      String name,
      String description,
      String status,
      String createdAt,
      String updatedAt,
      List<CatalogGroupElementResponse> elements) {}

  @Schema(name = "AssignCatalogGroupRequest")
  public record AssignCatalogGroupRequest(
      @Schema(description = "UUID of the catalog group to assign.") @NotBlank String groupId,
      @Schema(description = "Free-form JSON payload of this group's element values.")
          String elementVals) {}

  @Schema(name = "UpdateCatalogAssignmentRequest")
  public record UpdateCatalogAssignmentRequest(
      @Schema(description = "Free-form JSON payload of this group's element values.")
          String elementVals) {}

  @Schema(name = "CatalogAssignmentResponse")
  public record CatalogAssignmentResponse(
      String id,
      String variantId,
      @Schema(description = "UUID of the assigned catalog group.") String groupId,
      @Schema(description = "Free-form JSON payload of this group's element values.")
          String elementVals,
      String createdAt,
      String updatedAt) {}

  // ── Gap #39: Category sets ────────────────────────────────────────────────
  @Schema(name = "CreateCategorySetRequest")
  public record CreateCategorySetRequest(
      @NotBlank String name,
      String description,
      @Schema(
              description =
                  "Business purpose of this alternate hierarchy, e.g. \"TAX\", \"MERCHANDISING\".")
          @NotBlank
          String purpose,
      @Schema(description = "UUID of the default category for variants not explicitly assigned.")
          String defaultCatId,
      @Schema(description = "Whether membership is restricted (only admins can add categories).")
          boolean controlled) {}

  @Schema(name = "UpdateCategorySetRequest")
  public record UpdateCategorySetRequest(
      String name,
      String description,
      String purpose,
      @Schema(description = "UUID of the default category for variants not explicitly assigned.")
          String defaultCatId,
      boolean controlled,
      String status) {}

  @Schema(name = "CategorySetResponse")
  public record CategorySetResponse(
      String id,
      String name,
      String description,
      String purpose,
      @Schema(description = "UUID of the default category for variants not explicitly assigned.")
          String defaultCatId,
      boolean controlled,
      String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "AddCategorySetMemberRequest")
  public record AddCategorySetMemberRequest(
      @Schema(description = "UUID of the category to add to this set.") @NotBlank
          String categoryId) {}

  @Schema(name = "CategorySetMemberResponse")
  public record CategorySetMemberResponse(
      String id, String setId, String categoryId, String createdAt) {}

  @Schema(name = "AssignVariantCategorySetRequest")
  public record AssignVariantCategorySetRequest(
      @Schema(description = "UUID of the category set.") @NotBlank String setId,
      @Schema(description = "UUID of the category within that set.") @NotBlank String categoryId) {}

  @Schema(name = "VariantCategorySetAssignmentResponse")
  public record VariantCategorySetAssignmentResponse(
      String id,
      String variantId,
      @Schema(description = "UUID of the category set.") String setId,
      @Schema(description = "UUID of the category within that set.") String categoryId,
      String createdAt,
      String updatedAt) {}
}
