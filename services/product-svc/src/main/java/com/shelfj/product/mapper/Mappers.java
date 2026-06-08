package com.shelfj.product.mapper;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;

/** Entity → DTO conversion. */
public final class Mappers {

  private Mappers() {}

  public static BrandResponse toBrand(Brand b) {
    return new BrandResponse(b.id().toString(), b.name());
  }

  public static CategoryResponse toCategory(Category c) {
    return new CategoryResponse(
        c.id().toString(), c.parentId() == null ? null : c.parentId().toString(), c.name());
  }

  public static ProductResponse toProduct(Product p) {
    return new ProductResponse(
        p.id().toString(),
        p.name(),
        p.description(),
        p.brandId() == null ? null : p.brandId().toString(),
        p.categoryId() == null ? null : p.categoryId().toString(),
        p.status(),
        p.sellableOnline(),
        p.sellablePos());
  }

  public static VariantResponse toVariant(Variant v) {
    return new VariantResponse(
        v.id().toString(),
        v.productId().toString(),
        v.sku(),
        v.barcode(),
        v.attributes(),
        v.unit());
  }
}
