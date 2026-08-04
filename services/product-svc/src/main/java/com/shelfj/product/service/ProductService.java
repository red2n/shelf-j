package com.shelfj.product.service;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.CatalogGroup;
import com.shelfj.product.domain.Domain.CatalogGroupElement;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.CategorySet;
import com.shelfj.product.domain.Domain.CategorySetMember;
import com.shelfj.product.domain.Domain.ContainerType;
import com.shelfj.product.domain.Domain.ItemAttributeGroup;
import com.shelfj.product.domain.Domain.ItemAttributeGroupField;
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
import com.shelfj.product.domain.Domain.VariantAttributeGroupValues;
import com.shelfj.product.domain.Domain.VariantCatalogAssignment;
import com.shelfj.product.domain.Domain.VariantCategorySetAssignment;
import com.shelfj.product.domain.Domain.VariantContainerLink;
import com.shelfj.product.dto.Dtos.AddCategorySetMemberRequest;
import com.shelfj.product.dto.Dtos.AssignCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.AssignVariantCategorySetRequest;
import com.shelfj.product.dto.Dtos.BulkImportError;
import com.shelfj.product.dto.Dtos.BulkImportRequest;
import com.shelfj.product.dto.Dtos.BulkImportResult;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateCategorySetRequest;
import com.shelfj.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.shelfj.product.dto.Dtos.CreateItemRelationshipRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
import com.shelfj.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.shelfj.product.dto.Dtos.UpdateCategoryRequest;
import com.shelfj.product.dto.Dtos.UpdateCategorySetRequest;
import com.shelfj.product.dto.Dtos.UpdateProductRequest;
import com.shelfj.product.dto.Dtos.UpdateVariantRequest;
import com.shelfj.product.repo.BrandRepository;
import com.shelfj.product.repo.CatalogGroupRepository;
import com.shelfj.product.repo.CategoryRepository;
import com.shelfj.product.repo.CategorySetRepository;
import com.shelfj.product.repo.ContainerTypeRepository;
import com.shelfj.product.repo.ItemAttributeGroupRepository;
import com.shelfj.product.repo.ItemCrossReferenceRepository;
import com.shelfj.product.repo.ItemRelationshipRepository;
import com.shelfj.product.repo.ItemRevisionRepository;
import com.shelfj.product.repo.ItemTemplateRepository;
import com.shelfj.product.repo.ProductRepository;
import com.shelfj.product.repo.UomRepository;
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
  @Inject BrandRepository brandRepo;
  @Inject CategoryRepository categoryRepo;
  @Inject UomRepository uomRepo;
  @Inject ItemRevisionRepository itemRevisionRepo;
  @Inject ItemCrossReferenceRepository crossReferenceRepo;
  @Inject ItemRelationshipRepository itemRelationshipRepo;
  @Inject ItemTemplateRepository itemTemplateRepo;
  @Inject CatalogGroupRepository catalogGroupRepo;
  @Inject ContainerTypeRepository containerTypeRepo;
  @Inject ItemAttributeGroupRepository itemAttributeGroupRepo;
  @Inject CategorySetRepository categorySetRepo;
  @Inject com.shelfj.product.client.InventoryClient inventoryClient;
  @Inject com.shelfj.product.client.PricingClient pricingClient;

  // ─────────────────────────────────────────────────────────────────── brands

  public Brand createBrand(UUID tenantId, CreateBrandRequest req) {
    return brandRepo.createBrand(tenantId, req.name().trim());
  }

  public Brand getBrand(UUID tenantId, UUID id) {
    return brandRepo
        .findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  public List<Brand> listBrands(UUID tenantId) {
    return brandRepo.listBrands(tenantId);
  }

  public Brand renameBrand(UUID tenantId, UUID id, UpdateBrandRequest req) {
    getBrand(tenantId, id);
    return brandRepo.updateBrand(tenantId, id, req.name().trim());
  }

  public Brand deactivateBrand(UUID tenantId, UUID id) {
    getBrand(tenantId, id);
    return brandRepo.deactivateBrand(tenantId, id);
  }

  // ──────────────────────────────────────────────────────────────── categories

  public Category createCategory(UUID tenantId, CreateCategoryRequest req) {
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return categoryRepo.createCategory(tenantId, parentId, req.name().trim());
  }

  public Category getCategory(UUID tenantId, UUID id) {
    return categoryRepo
        .findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  public List<Category> listCategories(UUID tenantId) {
    return categoryRepo.listCategories(tenantId);
  }

  public Category updateCategory(UUID tenantId, UUID id, UpdateCategoryRequest req) {
    getCategory(tenantId, id);
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return categoryRepo.updateCategory(tenantId, id, req.name().trim(), parentId);
  }

  public Category deactivateCategory(UUID tenantId, UUID id) {
    getCategory(tenantId, id);
    return categoryRepo.deactivateCategory(tenantId, id);
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

  // ── product image ─────────────────────────────────────────────────────────

  /**
   * Hard ceiling on a stored product image: every image in the system is strictly smaller than
   * this. Thumbnails only — the stack has no object store, so bytes live in Postgres and are read
   * back in full on every storefront render.
   *
   * <p>Enforced here at the boundary and again as a CHECK constraint on {@code product_images} (see
   * V15), so the invariant holds whichever client writes — the admin app compresses to the same
   * budget before uploading, but nothing may depend on a client having done so.
   */
  public static final int MAX_IMAGE_BYTES = 256 * 1024;

  private static final java.util.Set<String> IMAGE_CONTENT_TYPES =
      java.util.Set.of("image/jpeg", "image/png", "image/webp");

  public void uploadProductImage(UUID tenantId, UUID productId, String contentType, byte[] bytes) {
    getProduct(tenantId, productId); // 404 before accepting bytes for a foreign/unknown product
    String normalized =
        contentType == null ? "" : contentType.trim().toLowerCase(java.util.Locale.ROOT);
    // Strip any ;charset= suffix a client might send
    int semi = normalized.indexOf(';');
    if (semi > 0) normalized = normalized.substring(0, semi).trim();
    if (!IMAGE_CONTENT_TYPES.contains(normalized))
      throw ApiException.badRequest(
          "PRODUCT_IMAGE_TYPE_INVALID",
          "Content-Type must be image/jpeg, image/png or image/webp — got: " + contentType);
    if (bytes == null || bytes.length == 0)
      throw ApiException.badRequest("PRODUCT_IMAGE_EMPTY", "image body is empty");
    // Strictly less than the ceiling — an image of exactly MAX_IMAGE_BYTES is rejected too.
    if (bytes.length >= MAX_IMAGE_BYTES)
      throw ApiException.badRequest(
          "PRODUCT_IMAGE_TOO_LARGE",
          "image is " + bytes.length + " bytes; must be under " + MAX_IMAGE_BYTES + " (256 KB)");
    repo.upsertProductImage(tenantId, productId, normalized, bytes);
  }

  public com.shelfj.product.domain.Domain.ProductImage getProductImage(
      UUID tenantId, UUID productId) {
    return repo.findProductImage(tenantId, productId)
        .orElseThrow(() -> ApiException.notFound("PRODUCT_IMAGE_NOT_FOUND", "no image"));
  }

  public void deleteProductImage(UUID tenantId, UUID productId) {
    getProduct(tenantId, productId);
    repo.deleteProductImage(tenantId, productId);
  }

  public List<Product> listProducts(
      UUID tenantId,
      UUID categoryId,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    return repo.listProducts(tenantId, categoryId, onlineOnly, posOnly, storeId, limit);
  }

  /** One page of admin products plus the opaque cursor for the next page (null when exhausted). */
  public record ProductPage(List<Product> products, String nextCursor) {}

  public ProductPage listProductsAdmin(
      UUID tenantId, UUID categoryId, String status, String afterCursor, int limit) {
    Instant afterCreatedAt = null;
    UUID afterId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCreatedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<Product> rows =
        repo.listProductsAdmin(tenantId, categoryId, status, afterCreatedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new ProductPage(rows, null);
    }
    List<Product> page = rows.subList(0, limit);
    Product last = page.get(page.size() - 1);
    return new ProductPage(
        page, com.shelfj.web.Cursor.encode(last.createdAt().toString() + "|" + last.id()));
  }

  public List<Product> searchProducts(
      UUID tenantId,
      String q,
      String sku,
      String barcode,
      boolean onlineOnly,
      boolean posOnly,
      UUID storeId,
      int limit) {
    return repo.searchProducts(tenantId, q, sku, barcode, onlineOnly, posOnly, storeId, limit);
  }

  /** Store ids a product is restricted to (empty = sold at all stores). */
  public List<UUID> getProductStores(UUID tenantId, UUID productId) {
    getProduct(tenantId, productId); // 404 if not in tenant
    return repo.storesForProduct(tenantId, productId);
  }

  /** Replace a product's store assortment. Empty list = sold at all stores. */
  public void setProductStores(UUID tenantId, UUID productId, List<UUID> storeIds) {
    getProduct(tenantId, productId); // 404 if not in tenant
    repo.setStoresForProduct(tenantId, productId, storeIds);
  }

  public com.shelfj.product.domain.Domain.VariantWithProduct findVariantByBarcode(
      UUID tenantId, String barcode) {
    return repo.findVariantByBarcode(tenantId, barcode)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "VARIANT_NOT_FOUND", "No active variant found for barcode: " + barcode));
  }

  /**
   * Batch-resolves variant ids to variant+product (e.g. so admin screens show names, not UUIDs).
   */
  public java.util.List<com.shelfj.product.domain.Domain.VariantWithProduct> resolveVariants(
      UUID tenantId, java.util.List<UUID> ids) {
    return repo.findVariantsByIds(tenantId, ids);
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
    UUID partyId = com.shelfj.web.Parsing.uuid(req.partyId(), "partyId");
    getVariant(tenantId, variantId);
    return crossReferenceRepo.createCrossReference(
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
    return crossReferenceRepo.listCrossReferences(tenantId, variantId, type);
  }

  public void deleteCrossReference(UUID tenantId, UUID id) {
    if (!crossReferenceRepo.deleteCrossReference(tenantId, id)) {
      throw ApiException.notFound("CROSS_REF_NOT_FOUND", "Cross reference not found");
    }
  }

  // ── Item Relationships (Gap #32) ────────────────────────────────────────

  public ItemRelationship createRelationship(
      UUID tenantId, UUID variantId, CreateItemRelationshipRequest req) {
    UUID relatedId = com.shelfj.web.Parsing.uuid(req.relatedVariantId(), "relatedVariantId");
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
    return itemRelationshipRepo.createRelationship(
        new ItemRelationship(
            UUID.randomUUID(), tenantId, variantId, relatedId, type, Instant.now()));
  }

  public List<ItemRelationship> listRelationships(UUID tenantId, UUID variantId) {
    getVariant(tenantId, variantId);
    return itemRelationshipRepo.listRelationships(tenantId, variantId);
  }

  public void deleteRelationship(UUID tenantId, UUID id) {
    if (!itemRelationshipRepo.deleteRelationship(tenantId, id)) {
      throw ApiException.notFound("RELATIONSHIP_NOT_FOUND", "Item relationship not found");
    }
  }

  // ---- UOM (Gap #2) ----

  public List<UomClass> listUomClasses() {
    return uomRepo.listUomClasses();
  }

  public List<UomDefinition> listUomDefinitions(String classCode) {
    return uomRepo.listUomDefinitions(classCode);
  }

  public UomItemConversion upsertItemConversion(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal factor) {
    return uomRepo.upsertItemConversion(
        new UomItemConversion(UUID.randomUUID(), tenantId, variantId, fromUom, toUom, factor));
  }

  public List<UomItemConversion> listItemConversions(UUID tenantId, UUID variantId) {
    return uomRepo.listItemConversions(tenantId, variantId);
  }

  public boolean deleteItemConversion(UUID tenantId, UUID id) {
    return uomRepo.deleteItemConversion(tenantId, id);
  }

  public ConvertResult convert(
      UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal qty) {
    if (fromUom.equalsIgnoreCase(toUom)) {
      return new ConvertResult(fromUom, toUom, qty, qty, BigDecimal.ONE, "IDENTITY");
    }
    if (variantId != null) {
      var itemFactor = uomRepo.findItemConversionFactor(tenantId, variantId, fromUom, toUom);
      if (itemFactor.isPresent()) {
        BigDecimal f = itemFactor.get();
        return new ConvertResult(fromUom, toUom, qty, qty.multiply(f), f, "ITEM");
      }
    }
    var stdFactor = uomRepo.findStandardConversionFactor(fromUom, toUom);
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
    return itemTemplateRepo.createTemplate(tpl, event);
  }

  public ItemTemplate getTemplate(UUID tenantId, UUID id) {
    return itemTemplateRepo
        .findTemplate(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
  }

  public List<ItemTemplate> listTemplates(UUID tenantId) {
    return itemTemplateRepo.listTemplates(tenantId);
  }

  public ItemTemplate deactivateTemplate(UUID tenantId, UUID id) {
    getTemplate(tenantId, id);
    return itemTemplateRepo.deactivateTemplate(tenantId, id);
  }

  public ItemTemplateApplication applyTemplate(UUID tenantId, UUID variantId, UUID templateId) {
    var event =
        new OutboxRow(
            "ItemTemplateApplied",
            "shelfj.catalog.item-template-applied",
            tenantId,
            variantId,
            Events.itemTemplateApplied(tenantId, variantId, templateId));
    return itemTemplateRepo.applyTemplate(tenantId, variantId, templateId, event);
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
    return itemRevisionRepo.createRevisionWithOutbox(rev, event);
  }

  public List<ItemRevision> listRevisions(UUID tenantId, UUID variantId) {
    return itemRevisionRepo.listRevisions(tenantId, variantId);
  }

  public ItemRevision currentRevision(UUID tenantId, UUID variantId) {
    return itemRevisionRepo
        .currentRevision(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound("REVISION_NOT_FOUND", "No active revision for this variant"));
  }

  public ItemRevision getRevision(UUID tenantId, UUID revisionId) {
    return itemRevisionRepo
        .findRevision(tenantId, revisionId)
        .orElseThrow(() -> ApiException.notFound("REVISION_NOT_FOUND", "No such revision"));
  }

  // ── Bulk Import ──────────────────────────────────────────────────────────

  public BulkImportResult bulkImport(UUID tenantId, BulkImportRequest req) {
    int catCreated = 0;
    int catSkipped = 0;
    int prodCreated = 0;
    int varCreated = 0;
    var errors = new java.util.ArrayList<BulkImportError>();
    var importedVariants = new java.util.ArrayList<com.shelfj.product.dto.Dtos.ImportedVariant>();
    // REPLACE = upsert by SKU (reuse product by name+category, replace existing variants);
    // ADD (default) = create new (duplicate SKUs error).
    final boolean replace = req.mode() != null && "REPLACE".equalsIgnoreCase(req.mode());

    // A CSV sheet typically has far fewer distinct category/brand names than product rows — cache
    // resolved ids by name within this import so repeated rows for the same category/brand don't
    // each cost a round trip.
    var categoryIdByName = new java.util.HashMap<String, UUID>();
    var brandIdByName = new java.util.HashMap<String, UUID>();

    // ── 1. categories ────────────────────────────────────────────────────────
    if (req.categories() != null) {
      for (var c : req.categories()) {
        try {
          com.shelfj.web.Validations.validate(c);
          var existingCat = categoryRepo.findCategoryByName(tenantId, c.name().trim());
          if (existingCat.isPresent()) {
            categoryIdByName.put(c.name().trim(), existingCat.get().id());
            catSkipped++;
            continue;
          }
          UUID parentId = null;
          if (c.parentName() != null && !c.parentName().isBlank()) {
            String parentName = c.parentName().trim();
            parentId = categoryIdByName.get(parentName);
            if (parentId == null) {
              parentId =
                  categoryRepo
                      .findCategoryByName(tenantId, parentName)
                      .map(cat -> cat.id())
                      .orElseThrow(
                          () ->
                              ApiException.badRequest(
                                  "PARENT_NOT_FOUND", "parent category not found: " + parentName));
            }
          }
          UUID newCategoryId =
              categoryRepo.createCategory(tenantId, parentId, c.name().trim()).id();
          categoryIdByName.put(c.name().trim(), newCategoryId);
          catCreated++;
        } catch (ApiException ae) {
          errors.add(new BulkImportError("category:" + c.name(), ae.getMessage()));
        } catch (Exception e) {
          errors.add(new BulkImportError("category:" + c.name(), "Failed to import category"));
        }
      }
    }

    // ── 2. products + variants ───────────────────────────────────────────────
    if (req.products() != null) {
      for (var p : req.products()) {
        try {
          com.shelfj.web.Validations.validate(p);
          if (p.variants() == null || p.variants().isEmpty()) {
            errors.add(new BulkImportError("product:" + p.name(), "at least one variant required"));
            continue;
          }

          UUID categoryId = null;
          if (p.categoryName() != null && !p.categoryName().isBlank()) {
            String categoryName = p.categoryName().trim();
            categoryId = categoryIdByName.get(categoryName);
            if (categoryId == null) {
              var found = categoryRepo.findCategoryByName(tenantId, categoryName);
              categoryId = found.map(cat -> cat.id()).orElse(null);
              if (categoryId != null) categoryIdByName.put(categoryName, categoryId);
            }
          }

          UUID brandId = null;
          if (p.brandName() != null && !p.brandName().isBlank()) {
            String brandName = p.brandName().trim();
            brandId = brandIdByName.get(brandName);
            if (brandId == null) {
              brandId =
                  brandRepo
                      .findBrandByName(tenantId, brandName)
                      .map(b -> b.id())
                      .orElseGet(() -> brandRepo.createBrand(tenantId, brandName).id());
              brandIdByName.put(brandName, brandId);
            }
          }

          Instant now = Instant.now();
          // REPLACE reuses an existing product (by name + category) instead of duplicating it;
          // ADD always creates a fresh product.
          UUID productId;
          var existing =
              replace
                  ? repo.findProductByNameAndCategory(tenantId, p.name().trim(), categoryId)
                  : java.util.Optional.<com.shelfj.product.domain.Domain.Product>empty();
          if (existing.isPresent()) {
            productId = existing.get().id();
          } else {
            productId = UUID.randomUUID();
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
          }

          // Assign to specific stores if requested (additive — never removes existing rows).
          if (p.storeIds() != null && !p.storeIds().isEmpty()) {
            var uuids =
                p.storeIds().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(UUID::fromString)
                    .toList();
            repo.addStoreAssignments(tenantId, productId, uuids);
          }

          for (var v : p.variants()) {
            try {
              com.shelfj.web.Validations.validate(v);
              // REPLACE: drop any existing variant with this SKU first, so the sheet wins.
              if (replace) repo.deleteVariantBySku(tenantId, v.sku().trim());
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
              importedVariants.add(
                  new com.shelfj.product.dto.Dtos.ImportedVariant(
                      variant.sku(), variantId.toString(), productId.toString()));
            } catch (ApiException ae) {
              errors.add(
                  new BulkImportError("variant:" + v.sku() + " on " + p.name(), ae.getMessage()));
            } catch (Exception e) {
              errors.add(
                  new BulkImportError(
                      "variant:" + v.sku() + " on " + p.name(),
                      "Failed to import variant — check SKU uniqueness"));
            }
          }
        } catch (ApiException ae) {
          errors.add(new BulkImportError("product:" + p.name(), ae.getMessage()));
        } catch (Exception e) {
          errors.add(new BulkImportError("product:" + p.name(), "Failed to import product"));
        }
      }
    }

    return new BulkImportResult(
        catCreated,
        catSkipped,
        prodCreated,
        varCreated,
        errors,
        importedVariants,
        null,
        null,
        null,
        null);
  }

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  public CatalogGroup createCatalogGroup(UUID tenantId, CreateCatalogGroupRequest req) {
    return catalogGroupRepo.createCatalogGroup(tenantId, req.name().trim(), req.description());
  }

  public CatalogGroup getCatalogGroup(UUID tenantId, UUID id) {
    return catalogGroupRepo
        .findCatalogGroup(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATALOG_GROUP_NOT_FOUND", "Catalog group not found"));
  }

  public List<CatalogGroup> listCatalogGroups(UUID tenantId) {
    return catalogGroupRepo.listCatalogGroups(tenantId);
  }

  public CatalogGroup deactivateCatalogGroup(UUID tenantId, UUID id) {
    getCatalogGroup(tenantId, id);
    return catalogGroupRepo.deactivateCatalogGroup(tenantId, id);
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
    return catalogGroupRepo.createCatalogGroupElement(
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
    return catalogGroupRepo.listCatalogGroupElements(tenantId, groupId);
  }

  public void deleteCatalogGroupElement(UUID tenantId, UUID elementId) {
    if (!catalogGroupRepo.deleteCatalogGroupElement(tenantId, elementId)) {
      throw ApiException.notFound("ELEMENT_NOT_FOUND", "Catalog group element not found");
    }
  }

  public VariantCatalogAssignment assignCatalogGroup(
      UUID tenantId, UUID variantId, AssignCatalogGroupRequest req) {
    getVariant(tenantId, variantId);
    UUID groupId = parseOptionalUuid(req.groupId(), "groupId");
    if (groupId == null) throw ApiException.badRequest("INVALID_GROUP_ID", "groupId is required");
    getCatalogGroup(tenantId, groupId);
    return catalogGroupRepo.createCatalogAssignment(
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
    return catalogGroupRepo
        .findCatalogAssignment(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant"));
  }

  public VariantCatalogAssignment updateCatalogAssignment(
      UUID tenantId, UUID variantId, UpdateCatalogAssignmentRequest req) {
    return catalogGroupRepo.updateCatalogAssignment(tenantId, variantId, req.elementVals());
  }

  public void deleteCatalogAssignment(UUID tenantId, UUID variantId) {
    if (!catalogGroupRepo.deleteCatalogAssignment(tenantId, variantId)) {
      throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant");
    }
  }

  // ── Container Types (Gap #37) ────────────────────────────────────────────

  public ContainerType createContainerType(
      UUID tenantId, com.shelfj.product.dto.Dtos.CreateContainerTypeRequest req) {
    return containerTypeRepo.createContainerType(
        tenantId,
        req.code().trim(),
        req.name().trim(),
        req.description(),
        req.lengthMm(),
        req.widthMm(),
        req.heightMm(),
        req.maxWeightKg(),
        req.tareWeightKg(),
        req.maxUnits());
  }

  public ContainerType getContainerType(UUID tenantId, UUID id) {
    return containerTypeRepo
        .findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public List<ContainerType> listContainerTypes(UUID tenantId) {
    return containerTypeRepo.listContainerTypes(tenantId);
  }

  public ContainerType updateContainerType(
      UUID tenantId, UUID id, com.shelfj.product.dto.Dtos.UpdateContainerTypeRequest req) {
    getContainerType(tenantId, id);
    return containerTypeRepo.updateContainerType(
        tenantId,
        id,
        req.name().trim(),
        req.description(),
        req.lengthMm(),
        req.widthMm(),
        req.heightMm(),
        req.maxWeightKg(),
        req.tareWeightKg(),
        req.maxUnits());
  }

  public ContainerType deactivateContainerType(UUID tenantId, UUID id) {
    getContainerType(tenantId, id);
    return containerTypeRepo.deactivateContainerType(tenantId, id);
  }

  public VariantContainerLink createVariantContainerLink(
      UUID tenantId,
      UUID variantId,
      com.shelfj.product.dto.Dtos.CreateVariantContainerLinkRequest req) {
    requireVariant(tenantId, variantId);
    UUID containerTypeId = UUID.fromString(req.containerTypeId());
    getContainerType(tenantId, containerTypeId);
    return containerTypeRepo.createVariantContainerLink(
        tenantId,
        variantId,
        containerTypeId,
        req.qtyPerContainer(),
        req.isPrimary() != null && req.isPrimary());
  }

  public List<VariantContainerLink> listVariantContainerLinks(UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return containerTypeRepo.listVariantContainerLinks(tenantId, variantId);
  }

  public void deleteVariantContainerLink(UUID tenantId, UUID id) {
    if (!containerTypeRepo.deleteVariantContainerLink(tenantId, id)) {
      throw ApiException.notFound("CONTAINER_LINK_NOT_FOUND", "Container link not found");
    }
  }

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  public List<ItemAttributeGroup> listAttributeGroups() {
    return itemAttributeGroupRepo.listAttributeGroups();
  }

  public ItemAttributeGroup getAttributeGroup(String groupCode) {
    return itemAttributeGroupRepo
        .findAttributeGroup(groupCode.toUpperCase(java.util.Locale.ROOT))
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_NOT_FOUND", "Attribute group not found: " + groupCode));
  }

  public List<ItemAttributeGroupField> listAttributeGroupFields(String groupCode) {
    return itemAttributeGroupRepo.listAttributeGroupFields(
        groupCode.toUpperCase(java.util.Locale.ROOT));
  }

  public VariantAttributeGroupValues upsertVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode, String values) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    itemAttributeGroupRepo
        .findAttributeGroup(code)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_NOT_FOUND", "Unknown attribute group: " + groupCode));
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo.upsertVariantAttributeGroupValues(
        tenantId, variantId, code, values);
  }

  public VariantAttributeGroupValues getVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo
        .findVariantAttributeGroupValues(tenantId, variantId, code)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_VALUES_NOT_FOUND",
                    "No attribute group values for group " + groupCode + " on this variant"));
  }

  public List<VariantAttributeGroupValues> listVariantAttributeGroupValues(
      UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return itemAttributeGroupRepo.listVariantAttributeGroupValues(tenantId, variantId);
  }

  public void deleteVariantAttributeGroupValues(UUID tenantId, UUID variantId, String groupCode) {
    String code = groupCode.toUpperCase(java.util.Locale.ROOT);
    if (!itemAttributeGroupRepo.deleteVariantAttributeGroupValues(tenantId, variantId, code)) {
      throw ApiException.notFound(
          "ATTRIBUTE_GROUP_VALUES_NOT_FOUND",
          "No attribute group values for group " + groupCode + " on this variant");
    }
  }

  // ── Gap #39: Category sets ────────────────────────────────────────────────

  public CategorySet createCategorySet(UUID tenantId, CreateCategorySetRequest req) {
    UUID defCat = parseOptionalUuid(req.defaultCatId(), "defaultCatId");
    return categorySetRepo.createCategorySet(
        new CategorySet(
            UUID.randomUUID(),
            tenantId,
            req.name(),
            req.description(),
            req.purpose(),
            defCat,
            req.controlled(),
            CategorySet.ACTIVE,
            null,
            null));
  }

  public List<CategorySet> listCategorySets(UUID tenantId) {
    return categorySetRepo.listCategorySets(tenantId);
  }

  public CategorySet getCategorySet(UUID tenantId, UUID id) {
    return categorySetRepo
        .findCategorySet(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATEGORY_SET_NOT_FOUND", "Category set not found"));
  }

  public CategorySet updateCategorySet(UUID tenantId, UUID id, UpdateCategorySetRequest req) {
    getCategorySet(tenantId, id);
    UUID defCat = parseOptionalUuid(req.defaultCatId(), "defaultCatId");
    return categorySetRepo.updateCategorySet(
        tenantId,
        id,
        req.name(),
        req.description(),
        req.purpose(),
        defCat,
        req.controlled(),
        req.status());
  }

  public void deleteCategorySet(UUID tenantId, UUID id) {
    if (!categorySetRepo.deleteCategorySet(tenantId, id)) {
      throw ApiException.notFound("CATEGORY_SET_NOT_FOUND", "Category set not found");
    }
  }

  public CategorySetMember addCategorySetMember(
      UUID tenantId, UUID setId, AddCategorySetMemberRequest req) {
    getCategorySet(tenantId, setId);
    UUID catId = UUID.fromString(req.categoryId());
    categoryRepo
        .findCategory(tenantId, catId)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
    return categorySetRepo.addCategorySetMember(
        new CategorySetMember(UUID.randomUUID(), tenantId, setId, catId, null));
  }

  public List<CategorySetMember> listCategorySetMembers(UUID tenantId, UUID setId) {
    getCategorySet(tenantId, setId);
    return categorySetRepo.listCategorySetMembers(tenantId, setId);
  }

  public void deleteCategorySetMember(UUID tenantId, UUID setId, UUID categoryId) {
    if (!categorySetRepo.deleteCategorySetMember(tenantId, setId, categoryId)) {
      throw ApiException.notFound(
          "CATEGORY_SET_MEMBER_NOT_FOUND", "Category not a member of this set");
    }
  }

  public VariantCategorySetAssignment assignVariantCategorySet(
      UUID tenantId, UUID variantId, AssignVariantCategorySetRequest req) {
    requireVariant(tenantId, variantId);
    UUID setId = UUID.fromString(req.setId());
    UUID catId = UUID.fromString(req.categoryId());
    getCategorySet(tenantId, setId);
    return categorySetRepo.upsertVariantCategorySetAssignment(
        new VariantCategorySetAssignment(
            UUID.randomUUID(), tenantId, variantId, setId, catId, null, null));
  }

  public List<VariantCategorySetAssignment> listVariantCategorySetAssignments(
      UUID tenantId, UUID variantId) {
    requireVariant(tenantId, variantId);
    return categorySetRepo.listVariantCategorySetAssignments(tenantId, variantId);
  }

  public void deleteVariantCategorySetAssignment(UUID tenantId, UUID variantId, UUID setId) {
    if (!categorySetRepo.deleteVariantCategorySetAssignment(tenantId, variantId, setId)) {
      throw ApiException.notFound(
          "CATEGORY_SET_ASSIGNMENT_NOT_FOUND", "Category set assignment not found");
    }
  }

  private void requireVariant(UUID tenantId, UUID variantId) {
    repo.findVariant(tenantId, variantId)
        .orElseThrow(() -> ApiException.notFound("VARIANT_NOT_FOUND", "Variant not found"));
  }

  private static UUID parseOptionalUuid(String s, String field) {
    return s == null || s.isBlank() ? null : com.shelfj.web.Parsing.uuid(s, field);
  }

  // ── Supplier CSV import ────────────────────────────────────────────────────

  /**
   * Lenient supplier catalogue import. Recognises these column headers (in any order,
   * case-insensitive):
   *
   * <ul>
   *   <li>{@code Product ID / product_id / id} → SKU; auto-generated from description if absent
   *   <li>{@code Category} → category (created if new; may be quoted)
   *   <li>{@code Product Description / description / name} → product name (required — row skipped
   *       if blank)
   *   <li>{@code Store / Store Name / store_name / outlet} → store assignment (resolved via
   *       storeNameToId)
   *   <li>{@code Quantity / Case Size / qty / packsize} → stored in attributes as caseSize
   *   <li>{@code Price / cost / trade price} → stored in attributes as tradePrice
   * </ul>
   *
   * <p>Rows that are entirely blank are skipped. No other validation is applied — whatever values
   * are present get imported as-is so the customer can correct data inside the system rather than
   * outside it.
   */
  public BulkImportResult importSupplierCsv(
      UUID tenantId, String rolesHeader, com.shelfj.product.dto.Dtos.SupplierCsvImportRequest req) {
    var storeNameToId =
        req.storeNameToId() != null ? req.storeNameToId() : java.util.Map.<String, String>of();
    var parsed = parseCsvFull(req.csv(), req.mode(), storeNameToId);
    var catalogResult = bulkImport(tenantId, parsed.request());

    Integer stockReceived = null;
    List<String> stockErrors = null;
    if (req.storeId() != null
        && !req.storeId().isBlank()
        && !catalogResult.importedVariants().isEmpty()) {
      var receiveItems =
          catalogResult.importedVariants().stream()
              .filter(v -> parsed.skuQty().containsKey(v.sku()))
              .map(
                  v ->
                      new com.shelfj.product.client.InventoryClient.ReceiveItem(
                          v.variantId(), parsed.skuQty().get(v.sku())))
              .toList();
      if (!receiveItems.isEmpty()) {
        var r =
            inventoryClient.batchReceive(
                tenantId, UUID.fromString(req.storeId()), rolesHeader, receiveItems);
        stockReceived = r.received();
        stockErrors = r.errors().isEmpty() ? null : r.errors();
      }
    }

    Integer pricesSet = null;
    List<String> priceErrors = null;
    if (!catalogResult.importedVariants().isEmpty()) {
      var priceItems =
          catalogResult.importedVariants().stream()
              .filter(v -> parsed.skuPrice().containsKey(v.sku()))
              .map(
                  v ->
                      new com.shelfj.product.client.PricingClient.PriceItem(
                          v.variantId(), parsed.skuPrice().get(v.sku())))
              .toList();
      if (!priceItems.isEmpty()) {
        String cur = (req.currency() != null && !req.currency().isBlank()) ? req.currency() : "GBP";
        var r = pricingClient.batchSetPrices(tenantId, cur, rolesHeader, priceItems);
        pricesSet = r.upserted();
        priceErrors = r.errors().isEmpty() ? null : r.errors();
      }
    }

    return new BulkImportResult(
        catalogResult.categoriesCreated(),
        catalogResult.categoriesSkipped(),
        catalogResult.productsCreated(),
        catalogResult.variantsCreated(),
        catalogResult.errors(),
        catalogResult.importedVariants(),
        stockReceived,
        stockErrors,
        pricesSet,
        priceErrors);
  }

  private record CsvParseResult(
      BulkImportRequest request,
      java.util.Map<String, BigDecimal> skuQty,
      java.util.Map<String, BigDecimal> skuPrice) {}

  private CsvParseResult parseCsvFull(
      String csv, String mode, java.util.Map<String, String> storeNameToId) {
    var skuQty = new java.util.HashMap<String, BigDecimal>();
    var skuPrice = new java.util.HashMap<String, BigDecimal>();
    var req = parseSupplierCsvToRequest(csv, mode, storeNameToId, skuQty, skuPrice);
    return new CsvParseResult(req, skuQty, skuPrice);
  }

  private record ProductEntry(
      String categoryName,
      java.util.List<com.shelfj.product.dto.Dtos.ImportVariantRequest> variants,
      java.util.Set<String> storeIds) {}

  private BulkImportRequest parseSupplierCsvToRequest(
      String csv,
      String mode,
      java.util.Map<String, String> storeNameToId,
      java.util.Map<String, BigDecimal> outSkuQty,
      java.util.Map<String, BigDecimal> outSkuPrice) {
    var lines =
        java.util.Arrays.asList(csv.split("\\r?\\n")).stream().filter(l -> !l.isBlank()).toList();
    if (lines.size() < 2) {
      throw new ApiException(
          400, "CSV_EMPTY", "CSV must have a header and at least one data row", List.of(), null);
    }

    // findHeader returns the FIRST matching column index — duplicate headers use the first one.
    var headers = splitCsvRow(lines.get(0));
    int idxId = findHeader(headers, "product id", "product_id", "sku", "item no");
    int idxDesc = findHeader(headers, "product description", "description", "product name", "name");
    int idxCat = findHeader(headers, "category");
    int idxQty = findHeader(headers, "quantity", "qty");
    int idxPrice = findHeader(headers, "price");
    int idxStore = findHeader(headers, "store", "store name", "store_name");

    if (idxDesc < 0) {
      throw new ApiException(
          400,
          "CSV_MISSING_COLUMNS",
          "CSV must have a 'Product Description' column",
          List.of(),
          null);
    }

    var categoryNames = new java.util.LinkedHashSet<String>();
    var productMap = new java.util.LinkedHashMap<String, ProductEntry>();
    int skuCounter = 0;

    for (int i = 1; i < lines.size(); i++) {
      var cols = splitCsvRow(lines.get(i));
      String desc = col(cols, idxDesc).trim();
      if (desc.isEmpty()) continue; // only skip genuinely blank name rows

      String sku = idxId >= 0 ? col(cols, idxId).trim() : "";
      if (sku.isEmpty()) {
        // Auto-generate a stable SKU from the description so duplicate rows collapse correctly.
        skuCounter++;
        sku = "IMP-" + skuCounter;
      }

      String category = idxCat >= 0 ? col(cols, idxCat).trim() : "";
      String qtyStr = idxQty >= 0 ? col(cols, idxQty).trim() : "";
      String priceStr = idxPrice >= 0 ? col(cols, idxPrice).trim() : "";

      // Capture numeric qty / price for stock-receive and pricing steps.
      if (!qtyStr.isEmpty() && outSkuQty != null) {
        try {
          outSkuQty.put(sku, new BigDecimal(qtyStr));
        } catch (NumberFormatException ignored) {
        }
      }
      if (!priceStr.isEmpty() && outSkuPrice != null) {
        try {
          outSkuPrice.put(sku, new BigDecimal(priceStr));
        } catch (NumberFormatException ignored) {
        }
      }

      // Build attributes from whatever is present — no parsing/validation.
      String attributes = buildAttributes(qtyStr, priceStr);

      var variant =
          new com.shelfj.product.dto.Dtos.ImportVariantRequest(sku, null, null, "CS", attributes);

      String storeName = idxStore >= 0 ? col(cols, idxStore).trim() : "";
      String storeId = storeName.isEmpty() ? null : storeNameToId.get(storeName);

      String key = desc + "|" + category;
      var entry = productMap.get(key);
      if (entry == null) {
        var storeIds = new java.util.LinkedHashSet<String>();
        if (storeId != null) storeIds.add(storeId);
        productMap.put(
            key, new ProductEntry(category, new java.util.ArrayList<>(List.of(variant)), storeIds));
      } else {
        entry.variants().add(variant);
        if (storeId != null) entry.storeIds().add(storeId);
      }

      if (!category.isEmpty()) categoryNames.add(category);
    }

    var categories =
        categoryNames.stream()
            .map(n -> new com.shelfj.product.dto.Dtos.ImportCategoryRequest(n, null))
            .toList();

    var products =
        productMap.entrySet().stream()
            .map(
                e -> {
                  var name = e.getKey().split("\\|", 2)[0];
                  var pe = e.getValue();
                  var storeIdList =
                      pe.storeIds().isEmpty() ? null : java.util.List.copyOf(pe.storeIds());
                  return new com.shelfj.product.dto.Dtos.ImportProductRequest(
                      name,
                      null,
                      pe.categoryName().isEmpty() ? null : pe.categoryName(),
                      null,
                      true,
                      true,
                      storeIdList,
                      pe.variants());
                })
            .toList();

    return new com.shelfj.product.dto.Dtos.BulkImportRequest(categories, products, mode);
  }

  private static String col(java.util.List<String> cols, int idx) {
    return (idx >= 0 && idx < cols.size()) ? cols.get(idx) : "";
  }

  private static int findHeader(java.util.List<String> headers, String... names) {
    for (String name : names) {
      for (int i = 0; i < headers.size(); i++) {
        if (headers.get(i).equalsIgnoreCase(name)) return i;
      }
    }
    return -1;
  }

  private static java.util.List<String> splitCsvRow(String line) {
    var result = new java.util.ArrayList<String>();
    var sb = new StringBuilder();
    boolean inQuotes = false;
    int pos = 0;
    while (pos < line.length()) {
      char ch = line.charAt(pos);
      if (ch == '"') {
        if (inQuotes && pos + 1 < line.length() && line.charAt(pos + 1) == '"') {
          sb.append('"');
          pos += 2;
        } else {
          inQuotes = !inQuotes;
          pos++;
        }
      } else if (ch == ',' && !inQuotes) {
        result.add(sb.toString());
        sb.setLength(0);
        pos++;
      } else {
        sb.append(ch);
        pos++;
      }
    }
    result.add(sb.toString());
    return result;
  }

  private static String buildAttributes(String caseSizeStr, String priceStr) {
    var sb = new StringBuilder("{");
    if (!caseSizeStr.isEmpty()) {
      sb.append("\"caseSize\":").append(caseSizeStr.replaceAll("[^0-9.]", ""));
    }
    if (!priceStr.isEmpty()) {
      if (sb.length() > 1) sb.append(",");
      sb.append("\"tradePrice\":\"").append(priceStr.replace("\"", "")).append("\"");
    }
    sb.append("}");
    return sb.length() > 2 ? sb.toString() : null;
  }
}
