package com.shelfj.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

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
}
