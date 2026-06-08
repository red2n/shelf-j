package com.shelfj.product.service;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.UpdateProductRequest;
import com.shelfj.product.repo.ProductRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Catalog business logic. Publishes catalog events via the outbox (golden rule #6). */
@ApplicationScoped
public class ProductService {

  @Inject ProductRepository repo;

  // --- brands / categories ---
  public Brand createBrand(UUID tenantId, CreateBrandRequest req) {
    return repo.createBrand(tenantId, req.name().trim());
  }

  public List<Brand> listBrands(UUID tenantId) {
    return repo.listBrands(tenantId);
  }

  public Category createCategory(UUID tenantId, CreateCategoryRequest req) {
    UUID parentId = parseOptionalUuid(req.parentId(), "parentId");
    return repo.createCategory(tenantId, parentId, req.name().trim());
  }

  public List<Category> listCategories(UUID tenantId) {
    return repo.listCategories(tenantId);
  }

  // --- products ---
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

  // --- variants ---
  public Variant createVariant(UUID tenantId, UUID productId, CreateVariantRequest req) {
    UUID id = UUID.randomUUID();
    var variant =
        new Variant(
            id,
            tenantId,
            productId,
            req.sku().trim(),
            req.barcode(),
            req.attributes(),
            req.unit(),
            Instant.now());
    var event =
        new OutboxRow(
            "VariantCreated",
            "shelfj.catalog.variant-created",
            tenantId,
            id,
            Events.variantCreated(tenantId, id, productId, variant.sku()));
    return repo.createVariantWithOutbox(variant, event);
  }

  public List<Variant> listVariants(UUID tenantId, UUID productId) {
    return repo.listVariants(tenantId, productId);
  }

  private static UUID parseOptionalUuid(String s, String field) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }
}
