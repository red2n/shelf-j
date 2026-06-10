package com.shelfj.product.mapper;

import com.shelfj.product.domain.Domain.Brand;
import com.shelfj.product.domain.Domain.Category;
import com.shelfj.product.domain.Domain.ItemRevision;
import com.shelfj.product.domain.Domain.ItemTemplate;
import com.shelfj.product.domain.Domain.ItemTemplateApplication;
import com.shelfj.product.domain.Domain.Product;
import com.shelfj.product.domain.Domain.UomClass;
import com.shelfj.product.domain.Domain.UomDefinition;
import com.shelfj.product.domain.Domain.UomItemConversion;
import com.shelfj.product.domain.Domain.Variant;
import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.ItemRevisionResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateApplicationResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.UomClassResponse;
import com.shelfj.product.dto.Dtos.UomDefinitionResponse;
import com.shelfj.product.dto.Dtos.UomItemConversionResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
import java.time.Instant;

/** Entity → DTO conversion. */
public final class Mappers {

  private Mappers() {}

  public static BrandResponse toBrand(Brand b) {
    return new BrandResponse(
        b.id().toString(), b.name(), b.status(), ts(b.createdAt()), ts(b.updatedAt()));
  }

  public static CategoryResponse toCategory(Category c) {
    return new CategoryResponse(
        c.id().toString(),
        c.parentId() == null ? null : c.parentId().toString(),
        c.name(),
        c.status(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
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
        p.sellablePos(),
        ts(p.createdAt()),
        ts(p.updatedAt()));
  }

  public static VariantResponse toVariant(Variant v) {
    return new VariantResponse(
        v.id().toString(),
        v.productId().toString(),
        v.sku(),
        v.barcode(),
        v.manufacturerPn(),
        v.attributes(),
        v.unit(),
        v.status(),
        ts(v.createdAt()),
        ts(v.updatedAt()));
  }

  public static UomClassResponse toUomClass(UomClass c) {
    return new UomClassResponse(c.code(), c.name());
  }

  public static UomDefinitionResponse toUomDefinition(UomDefinition d) {
    return new UomDefinitionResponse(d.code(), d.name(), d.classCode());
  }

  public static UomItemConversionResponse toUomItemConversion(UomItemConversion c) {
    return new UomItemConversionResponse(
        c.id().toString(), c.variantId().toString(), c.fromUom(), c.toUom(), c.factor());
  }

  public static ItemTemplateResponse toTemplate(ItemTemplate t) {
    return new ItemTemplateResponse(
        t.id().toString(),
        t.name(),
        t.description(),
        t.attributes(),
        t.status(),
        ts(t.createdAt()));
  }

  public static ItemTemplateApplicationResponse toTemplateApplication(ItemTemplateApplication a) {
    return new ItemTemplateApplicationResponse(
        a.id().toString(), a.variantId().toString(), a.templateId().toString(), ts(a.appliedAt()));
  }

  public static ItemRevisionResponse toRevision(ItemRevision r) {
    return new ItemRevisionResponse(
        r.id().toString(),
        r.variantId().toString(),
        r.revision(),
        r.description(),
        r.effectiveDate().toString(),
        r.status(),
        ts(r.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
