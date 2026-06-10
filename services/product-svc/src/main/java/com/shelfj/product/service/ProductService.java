package com.shelfj.product.service;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.CatalogGroup;
import com.shelfj.product.domain.Domain.CatalogGroupElement;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.ItemCrossReference;
import com.shelfj.product.domain.Domain.ItemRelationship;
import com.shelfj.product.domain.Domain.ItemRevision;
import com.shelfj.product.domain.Domain.ItemTemplate;
import com.shelfj.product.domain.Domain.ItemTemplateApplication;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.UomClass;
import com.shelfj.product.domain.Domain.UomDefinition;
import com.shelfj.product.domain.Domain.UomItemConversion;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.domain.Domain.VariantCatalogAssignment;
import com.shelfj.product.dto.Dtos.AssignCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.BulkImportError;
import com.shelfj.product.dto.Dtos.BulkImportRequest;
import com.shelfj.product.dto.Dtos.BulkImportResult;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.shelfj.product.dto.Dtos.CreateItemRelationshipRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
import com.shelfj.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.shelfj.product.dto.Dtos.UpdateCategoryRequest;
import com.shelfj.product.dto.Dtos.UpdateProductRequest;
import com.shelfj.product.dto.Dtos.UpdateVariantRequest;
import com.shelfj.product.repo.ProductRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Catalog business logic. Publishes catalog events via the outbox (golden rule #6). */
@ApplicationScoped
public class ProductService {

  @Inject ProductRepository repo;

  // ─────────────────────────────────────────────────────────────────── brands

  public Brand createBrand(UUID tenantId, CreateBrandRequest req) {
    return repo.createBrand(tenantId, req.name().trim());
  }

  public Brand getBrand(UUID tenantId, UUID id) {
    return repo.findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  public List<Brand> listBrands(UUID tenantId) {
    return repo.listBrands(tenantId);
  }

  public Brand renameBrand(UUID tenantId, UUID id, UpdateBrandRequest req) {
    getBrand(tenantId, id);
    return repo.updateBrand(tenantId, id, req.name().trim());
  }

  public Brand deactivateBrand(UUID tenantId, UUID id) {
    getBrand(tenantId, id);
    return repo.deactivateBrand(tenantId, id);
  }

  // ──────────────────────────────────────────────────────────────── categories

  public Category createCategory(UUID tenantId, CreateCategoryRequest req) {
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return repo.createCategory(tenantId, parentId, req.name().trim());
  }

  public Category getCategory(UUID tenantId, UUID id) {
    return repo.findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  public List<Category> listCategories(UUID tenantId) {
    return repo.listCategories(tenantId);
  }

  public Category updateCategory(UUID tenantId, UUID id, UpdateCategoryRequest req) {
    getCategory(tenantId, id);
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return repo.updateCategory(tenantId, id, req.name().trim(), parentId);
  }

  public Category deactivateCategory(UUID tenantId, UUID id) {
    getCategory(tenantId, id);
    return repo.deactivateCategory(tenantId, id);
  }

  // ──────────────────────────────────────────────────────────────── products

  public Product createProduct(UUID tenantId, CreateProductRequest req) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    var product =
        new Product(
            id,
            tenantId,
            req.name().trim(),
            req.description(),
            parseOptionalUuid(req.brandId(), "brandId"),
            parseOptionalUuid(req.categoryId(), "categoryId"),
            Product.STATUS_ACTIVE,
            req.sellableOnline() == null || req.sellableOnline(),
            req.sellablePos() == null || req.sellablePos(),
            now,
            now);
    var event =
        new OutboxRow(
            "ProductCreated",
            "shelfj.catalog.product-created",
            tenantId,
            id,
            Events.productCreated(tenantId, id, product.name()));
    return repo.createProductWithOutbox(product, event);
  }

  public Product updateProduct(UUID tenantId, UUID productId, UpdateProductRequest req) {
    Product existing =
        repo.findProduct(tenantId, productId)
            .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
    var updated =
        new Product(
            existing.id(),
            tenantId,
            req.name().trim(),
            req.description(),
            parseOptionalUuid(req.brandId(), "brandId"),
            parseOptionalUuid(req.categoryId(), "categoryId"),
            existing.status(),
            req.sellableOnline(),
            req.sellablePos(),
            existing.createdAt(),
            Instant.now());
    var event =
        new OutboxRow(
            "ProductUpdated",
            "shelfj.catalog.product-updated",
            tenantId,
            productId,
            Events.productUpdated(tenantId, productId, updated.status()));
    return repo.updateProductWithOutbox(updated, event);
  }

  /** Delist a product (soft) — sets status DELISTED, publishes ProductDelisted. */
  public Product delistProduct(UUID tenantId, UUID productId) {
    Product existing =
        repo.findProduct(tenantId, productId)
            .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
    var delisted =
        new Product(
            existing.id(),
            tenantId,
            existing.name(),
            existing.description(),
            existing.brandId(),
            existing.categoryId(),
            Product.STATUS_DELISTED,
            existing.sellableOnline(),
            existing.sellablePos(),
            existing.createdAt(),
            Instant.now());
    var event =
        new OutboxRow(
            "ProductDelisted",
            "shelfj.catalog.product-delisted",
            tenantId,
            productId,
            Events.productDelisted(tenantId, productId));
    return repo.updateProductWithOutbox(delisted, event);
  }

  public Product getProduct(UUID tenantId, UUID productId) {
    return repo.findProduct(tenantId, productId)
        .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No such product"));
  }

  public List<Product> listProducts(UUID tenantId, UUID categoryId, boolean onlineOnly, int limit) {
    return repo.listProducts(tenantId, categoryId, onlineOnly, limit);
  }

  public List<Product> listProductsAdmin(UUID tenantId, UUID categoryId, String status, int limit) {
    return repo.listProductsAdmin(tenantId, categoryId, status, limit);
  }

  // ──────────────────────────────────────────────────────────────── variants

  public Variant createVariant(UUID tenantId, UUID productId, CreateVariantRequest req) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    var variant =
        new Variant(
            id,
            tenantId,
            productId,
            req.sku().trim(),
            req.barcode(),
            req.manufacturerPn(),
            req.attributes(),
            req.unit(),
            Variant.STATUS_ACTIVE,
            now,
            now);
    var event =
        new OutboxRow(
            "VariantCreated",
            "shelfj.catalog.variant-created",
            tenantId,
            id,
            Events.variantCreated(tenantId, id, productId, variant.sku()));
    return repo.createVariantWithOutbox(variant, event);
  }

  public Variant getVariant(UUID tenantId, UUID variantId) {
    return repo.findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  public List<Variant> listVariants(UUID tenantId, UUID productId) {
    return repo.listVariants(tenantId, productId);
  }

  public Variant updateVariant(
      UUID tenantId, UUID productId, UUID variantId, UpdateVariantRequest req) {
    getVariant(tenantId, variantId);
    return repo.updateVariant(
        tenantId,
        variantId,
        req.sku().trim(),
        req.barcode(),
        req.manufacturerPn(),
        req.attributes(),
        req.unit());
  }

  public Variant delistVariant(UUID tenantId, UUID productId, UUID variantId) {
    getVariant(tenantId, variantId);
    return repo.delistVariant(tenantId, variantId);
  }

  // ── Supplier / Customer Cross-References (Gap #33) ──────────────────────

  public ItemCrossReference createCrossReference(
      UUID tenantId, UUID variantId, CreateItemCrossReferenceRequest req) {
    String type = req.partyType().toUpperCase(java.util.Locale.ROOT);
    if (!ItemCrossReference.SUPPLIER.equals(type) && !ItemCrossReference.CUSTOMER.equals(type)) {
      throw ApiException.badRequest("INVALID_PARTY_TYPE", "partyType must be SUPPLIER or CUSTOMER");
    }
    UUID partyId;
    try {
      partyId = UUID.fromString(req.partyId());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", "partyId must be a UUID", List.of(), e);
    }
    getVariant(tenantId, variantId);
    return repo.createCrossReference(
        new ItemCrossReference(
            UUID.randomUUID(),
            tenantId,
            variantId,
            type,
            partyId,
            req.partyName(),
            req.crossRefNumber().trim(),
            Instant.now()));
  }

  public List<ItemCrossReference> listCrossReferences(
      UUID tenantId, UUID variantId, String partyType) {
    getVariant(tenantId, variantId);
    String type = partyType != null ? partyType.toUpperCase(java.util.Locale.ROOT) : null;
    return repo.listCrossReferences(tenantId, variantId, type);
  }

  public void deleteCrossReference(UUID tenantId, UUID id) {
    if (!repo.deleteCrossReference(tenantId, id)) {
      throw ApiException.notFound("CROSS_REF_NOT_FOUND", "Cross reference not found");
    }
  }

  // ── Item Relationships (Gap #32) ────────────────────────────────────────

  public ItemRelationship createRelationship(
      UUID tenantId, UUID variantId, CreateItemRelationshipRequest req) {
    UUID relatedId;
    try {
      relatedId = UUID.fromString(req.relatedVariantId());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", "relatedVariantId must be a UUID", List.of(), e);
    }
    String type = req.relationshipType().toUpperCase(java.util.Locale.ROOT);
    if (!ItemRelationship.SUBSTITUTE.equals(type) && !ItemRelationship.COMPLEMENTARY.equals(type)) {
      throw ApiException.badRequest(
          "INVALID_RELATIONSHIP_TYPE", "relationshipType must be SUBSTITUTE or COMPLEMENTARY");
    }
    if (variantId.equals(relatedId)) {
      throw ApiException.badRequest("SELF_RELATIONSHIP", "A variant cannot relate to itself");
    }
    getVariant(tenantId, variantId);
    getVariant(tenantId, relatedId);
    return repo.createRelationship(
        new ItemRelationship(
            UUID.randomUUID(), tenantId, variantId, relatedId, type, Instant.now()));
  }

  public List<ItemRelationship> listRelationships(UUID tenantId, UUID variantId) {
    getVariant(tenantId, variantId);
    return repo.listRelationships(tenantId, variantId);
  }

  public void deleteRelationship(UUID tenantId, UUID id) {
    if (!repo.deleteRelationship(tenantId, id)) {
      throw ApiException.notFound("RELATIONSHIP_NOT_FOUND", "Item relationship not found");
    }
  }

  // ---- UOM (Gap #2) ----

  public List<UomClass> listUomClasses() {
    return repo.listUomClasses();
  }

  public List<UomDefinition> listUomDefinitions(String classCode) {
    return repo.listUomDefinitions(classCode);
  }

  public UomItemConversion upsertItemConversion(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal factor) {
    return repo.upsertItemConversion(
        new UomItemConversion(UUID.randomUUID(), tenantId, variantId, fromUom, toUom, factor));
  }

  public List<UomItemConversion> listItemConversions(UUID tenantId, UUID variantId) {
    return repo.listItemConversions(tenantId, variantId);
  }

  public boolean deleteItemConversion(UUID tenantId, UUID id) {
    return repo.deleteItemConversion(tenantId, id);
  }

  public ConvertResult convert(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal qty) {
    if (fromUom.equalsIgnoreCase(toUom)) {
      return new ConvertResult(fromUom, toUom, qty, qty, BigDecimal.ONE, "IDENTITY");
    }
    if (variantId != null) {
      var itemFactor = repo.findItemConversionFactor(tenantId, variantId, fromUom, toUom);
      if (itemFactor.isPresent()) {
        BigDecimal f = itemFactor.get();
        return new ConvertResult(fromUom, toUom, qty, qty.multiply(f), f, "ITEM");
      }
    }
    var stdFactor = repo.findStandardConversionFactor(fromUom, toUom);
    if (stdFactor.isPresent()) {
      BigDecimal f = stdFactor.get();
      return new ConvertResult(fromUom, toUom, qty, qty.multiply(f), f, "STANDARD");
    }
    throw new ApiException(
        404,
        "CONVERSION_NOT_FOUND",
        "No conversion from " + fromUom + " to " + toUom,
        List.of(),
        null);
  }

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  public ItemTemplate createTemplate(
      UUID tenantId, String name, String description, String attributes) {
    UUID id = UUID.randomUUID();
    var tpl =
        new ItemTemplate(
            id, tenantId, name, description, attributes, ItemTemplate.ACTIVE, Instant.now());
    var event =
        new OutboxRow(
            "ItemTemplateCreated",
            "shelfj.catalog.item-template-created",
            tenantId,
            id,
            Events.itemTemplateCreated(tenantId, id, name));
    return repo.createTemplate(tpl, event);
  }

  public ItemTemplate getTemplate(UUID tenantId, UUID id) {
    return repo.findTemplate(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
  }

  public List<ItemTemplate> listTemplates(UUID tenantId) {
    return repo.listTemplates(tenantId);
  }

  public ItemTemplate deactivateTemplate(UUID tenantId, UUID id) {
    getTemplate(tenantId, id);
    return repo.deactivateTemplate(tenantId, id);
  }

  public ItemTemplateApplication applyTemplate(UUID tenantId, UUID variantId, UUID templateId) {
    var event =
        new OutboxRow(
            "ItemTemplateApplied",
            "shelfj.catalog.item-template-applied",
            tenantId,
            variantId,
            Events.itemTemplateApplied(tenantId, variantId, templateId));
    return repo.applyTemplate(tenantId, variantId, templateId, event);
  }

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  public ItemRevision createRevision(
      UUID tenantId, UUID variantId, String revision, String description, LocalDate effectiveDate) {
    UUID id = UUID.randomUUID();
    var rev =
        new ItemRevision(
            id,
            tenantId,
            variantId,
            revision,
            description,
            effectiveDate,
            ItemRevision.ACTIVE,
            Instant.now());
    var event =
        new OutboxRow(
            "ItemRevisionCreated",
            "shelfj.catalog.item-revision-created",
            tenantId,
            id,
            Events.itemRevisionCreated(tenantId, variantId, id, revision));
    return repo.createRevisionWithOutbox(rev, event);
  }

  public List<ItemRevision> listRevisions(UUID tenantId, UUID variantId) {
    return repo.listRevisions(tenantId, variantId);
  }

  public ItemRevision currentRevision(UUID tenantId, UUID variantId) {
    return repo.currentRevision(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound("REVISION_NOT_FOUND", "No active revision for this variant"));
  }

  public ItemRevision getRevision(UUID tenantId, UUID revisionId) {
    return repo.findRevision(tenantId, revisionId)
        .orElseThrow(() -> ApiException.notFound("REVISION_NOT_FOUND", "No such revision"));
  }

  // ── Bulk Import ──────────────────────────────────────────────────────────

  public BulkImportResult bulkImport(UUID tenantId, BulkImportRequest req) {
    int catCreated = 0, catSkipped = 0, prodCreated = 0, varCreated = 0;
    var errors = new java.util.ArrayList<BulkImportError>();

    // ── 1. categories ────────────────────────────────────────────────────────
    if (req.categories() != null) {
      for (var c : req.categories()) {
        try {
          if (repo.findCategoryByName(tenantId, c.name().trim()).isPresent()) {
            catSkipped++;
            continue;
          }
          UUID parentId = null;
          if (c.parentName() != null && !c.parentName().isBlank()) {
            parentId =
                repo.findCategoryByName(tenantId, c.parentName().trim())
                    .map(cat -> cat.id())
                    .orElseThrow(
                        () ->
                            ApiException.badRequest(
                                "PARENT_NOT_FOUND",
                                "parent category not found: " + c.parentName()));
          }
          repo.createCategory(tenantId, parentId, c.name().trim());
          catCreated++;
        } catch (Exception e) {
          errors.add(new BulkImportError("category:" + c.name(), e.getMessage()));
        }
      }
    }

    // ── 2. products + variants ───────────────────────────────────────────────
    if (req.products() != null) {
      for (var p : req.products()) {
        try {
          if (p.variants() == null || p.variants().isEmpty()) {
            errors.add(new BulkImportError("product:" + p.name(), "at least one variant required"));
            continue;
          }

          UUID categoryId =
              p.categoryName() != null && !p.categoryName().isBlank()
                  ? repo.findCategoryByName(tenantId, p.categoryName().trim())
                      .map(cat -> cat.id())
                      .orElse(null)
                  : null;

          UUID brandId = null;
          if (p.brandName() != null && !p.brandName().isBlank()) {
            brandId =
                repo.findBrandByName(tenantId, p.brandName().trim())
                    .map(b -> b.id())
                    .orElseGet(() -> repo.createBrand(tenantId, p.brandName().trim()).id());
          }

          UUID productId = UUID.randomUUID();
          Instant now = Instant.now();
          var product =
              new com.shelfj.product.domain.Domain.Product(
                  productId,
                  tenantId,
                  p.name().trim(),
                  p.description(),
                  brandId,
                  categoryId,
                  com.shelfj.product.domain.Domain.Product.STATUS_ACTIVE,
                  p.sellableOnline() == null || p.sellableOnline(),
                  p.sellablePos() == null || p.sellablePos(),
                  now,
                  now);
          var productEvent =
              new OutboxRow(
                  "ProductCreated",
                  "shelfj.catalog.product-created",
                  tenantId,
                  productId,
                  Events.productCreated(tenantId, productId, product.name()));
          repo.createProductWithOutbox(product, productEvent);
          prodCreated++;

          for (var v : p.variants()) {
            try {
              UUID variantId = UUID.randomUUID();
              var variant =
                  new com.shelfj.product.domain.Domain.Variant(
                      variantId,
                      tenantId,
                      productId,
                      v.sku().trim(),
                      v.barcode(),
                      v.manufacturerPn(),
                      v.attributes(),
                      v.unit(),
                      com.shelfj.product.domain.Domain.Variant.STATUS_ACTIVE,
                      now,
                      now);
              var variantEvent =
                  new OutboxRow(
                      "VariantCreated",
                      "shelfj.catalog.variant-created",
                      tenantId,
                      variantId,
                      Events.variantCreated(tenantId, variantId, productId, variant.sku()));
              repo.createVariantWithOutbox(variant, variantEvent);
              varCreated++;
            } catch (Exception e) {
              errors.add(
                  new BulkImportError("variant:" + v.sku() + " on " + p.name(), e.getMessage()));
            }
          }
        } catch (Exception e) {
          errors.add(new BulkImportError("product:" + p.name(), e.getMessage()));
        }
      }
    }

    return new BulkImportResult(catCreated, catSkipped, prodCreated, varCreated, errors);
  }

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  public CatalogGroup createCatalogGroup(UUID tenantId, CreateCatalogGroupRequest req) {
    return repo.createCatalogGroup(tenantId, req.name().trim(), req.description());
  }

  public CatalogGroup getCatalogGroup(UUID tenantId, UUID id) {
    return repo.findCatalogGroup(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATALOG_GROUP_NOT_FOUND", "Catalog group not found"));
  }

  public List<CatalogGroup> listCatalogGroups(UUID tenantId) {
    return repo.listCatalogGroups(tenantId);
  }

  public CatalogGroup deactivateCatalogGroup(UUID tenantId, UUID id) {
    getCatalogGroup(tenantId, id);
    return repo.deactivateCatalogGroup(tenantId, id);
  }

  public CatalogGroupElement createCatalogGroupElement(
      UUID tenantId, UUID groupId, CreateCatalogGroupElementRequest req) {
    getCatalogGroup(tenantId, groupId);
    String type = req.dataType().toUpperCase(java.util.Locale.ROOT);
    if (!CatalogGroupElement.TYPE_TEXT.equals(type)
        && !CatalogGroupElement.TYPE_NUMBER.equals(type)
        && !CatalogGroupElement.TYPE_BOOLEAN.equals(type)
        && !CatalogGroupElement.TYPE_DATE.equals(type)) {
      throw ApiException.badRequest(
          "INVALID_DATA_TYPE", "dataType must be TEXT, NUMBER, BOOLEAN or DATE");
    }
    return repo.createCatalogGroupElement(
        new CatalogGroupElement(
            UUID.randomUUID(),
            tenantId,
            groupId,
            req.elementName().trim(),
            type,
            req.required(),
            req.defaultVal(),
            req.sortOrder(),
            Instant.now()));
  }

  public List<CatalogGroupElement> listCatalogGroupElements(UUID tenantId, UUID groupId) {
    return repo.listCatalogGroupElements(tenantId, groupId);
  }

  public void deleteCatalogGroupElement(UUID tenantId, UUID elementId) {
    if (!repo.deleteCatalogGroupElement(tenantId, elementId)) {
      throw ApiException.notFound("ELEMENT_NOT_FOUND", "Catalog group element not found");
    }
  }

  public VariantCatalogAssignment assignCatalogGroup(
      UUID tenantId, UUID variantId, AssignCatalogGroupRequest req) {
    getVariant(tenantId, variantId);
    UUID groupId = parseOptionalUuid(req.groupId(), "groupId");
    if (groupId == null) throw ApiException.badRequest("INVALID_GROUP_ID", "groupId is required");
    getCatalogGroup(tenantId, groupId);
    return repo.createCatalogAssignment(
        new VariantCatalogAssignment(
            UUID.randomUUID(),
            tenantId,
            variantId,
            groupId,
            req.elementVals() != null ? req.elementVals() : "{}",
            Instant.now(),
            Instant.now()));
  }

  public VariantCatalogAssignment getCatalogAssignment(UUID tenantId, UUID variantId) {
    return repo.findCatalogAssignment(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant"));
  }

  public VariantCatalogAssignment updateCatalogAssignment(
      UUID tenantId, UUID variantId, UpdateCatalogAssignmentRequest req) {
    return repo.updateCatalogAssignment(tenantId, variantId, req.elementVals());
  }

  public void deleteCatalogAssignment(UUID tenantId, UUID variantId) {
    if (!repo.deleteCatalogAssignment(tenantId, variantId)) {
      throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant");
    }
  }

  private static UUID parseOptionalUuid(String s, String field) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }
}
