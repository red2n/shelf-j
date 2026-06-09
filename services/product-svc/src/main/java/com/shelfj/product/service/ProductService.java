package com.shelfj.product.service;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.UomClass;
import com.shelfj.product.domain.Domain.UomDefinition;
import com.shelfj.product.domain.Domain.UomItemConversion;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
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
        tenantId, variantId, req.sku().trim(), req.barcode(), req.attributes(), req.unit());
  }

  public Variant delistVariant(UUID tenantId, UUID productId, UUID variantId) {
    getVariant(tenantId, variantId);
    return repo.delistVariant(tenantId, variantId);
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

  private static UUID parseOptionalUuid(String s, String field) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }
}
