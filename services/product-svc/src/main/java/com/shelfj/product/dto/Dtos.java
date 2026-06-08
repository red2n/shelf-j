package com.shelfj.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request/response DTOs for product-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // requests
  public record CreateBrandRequest(@NotBlank String name) {}

  public record CreateCategoryRequest(@NotBlank String name, String parentId) {}

  public record CreateProductRequest(
      @NotBlank String name,
      String description,
      String brandId,
      String categoryId,
      Boolean sellableOnline,
      Boolean sellablePos) {}

  public record UpdateProductRequest(
      @NotBlank String name,
      String description,
      String brandId,
      String categoryId,
      @NotNull Boolean sellableOnline,
      @NotNull Boolean sellablePos) {}

  public record CreateVariantRequest(
      @NotBlank String sku, String barcode, String attributes, String unit) {}

  // responses
  public record BrandResponse(String id, String name) {}

  public record CategoryResponse(String id, String parentId, String name) {}

  public record ProductResponse(
      String id,
      String name,
      String description,
      String brandId,
      String categoryId,
      String status,
      boolean sellableOnline,
      boolean sellablePos) {}

  public record VariantResponse(
      String id, String productId, String sku, String barcode, String attributes, String unit) {}
}
