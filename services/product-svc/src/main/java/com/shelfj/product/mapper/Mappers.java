package com.shelfj.product.mapper;

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
import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.CatalogAssignmentResponse;
import com.shelfj.product.dto.Dtos.CatalogGroupElementResponse;
import com.shelfj.product.dto.Dtos.CatalogGroupResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.CategorySetMemberResponse;
import com.shelfj.product.dto.Dtos.CategorySetResponse;
import com.shelfj.product.dto.Dtos.ContainerTypeResponse;
import com.shelfj.product.dto.Dtos.ItemAttributeGroupFieldResponse;
import com.shelfj.product.dto.Dtos.ItemAttributeGroupResponse;
import com.shelfj.product.dto.Dtos.ItemCrossReferenceResponse;
import com.shelfj.product.dto.Dtos.ItemRelationshipResponse;
import com.shelfj.product.dto.Dtos.ItemRevisionResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateApplicationResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.UomClassResponse;
import com.shelfj.product.dto.Dtos.UomDefinitionResponse;
import com.shelfj.product.dto.Dtos.UomItemConversionResponse;
import com.shelfj.product.dto.Dtos.VariantAttributeGroupValuesResponse;
import com.shelfj.product.dto.Dtos.VariantCategorySetAssignmentResponse;
import com.shelfj.product.dto.Dtos.VariantContainerLinkResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
import com.shelfj.product.dto.Dtos.VariantScanResponse;
import java.time.Instant;
import java.util.List;

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

  public static VariantScanResponse toVariantScan(Variant v, Product p) {
    return new VariantScanResponse(
        v.id().toString(),
        p.id().toString(),
        p.name(),
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

  public static ItemCrossReferenceResponse toCrossReference(ItemCrossReference x) {
    return new ItemCrossReferenceResponse(
        x.id().toString(),
        x.variantId().toString(),
        x.partyType(),
        x.partyId().toString(),
        x.partyName(),
        x.crossRefNumber(),
        ts(x.createdAt()));
  }

  public static ItemRelationshipResponse toRelationship(ItemRelationship r) {
    return new ItemRelationshipResponse(
        r.id().toString(),
        r.variantId().toString(),
        r.relatedVariantId().toString(),
        r.relationshipType(),
        ts(r.createdAt()));
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

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  public static CatalogGroupResponse toCatalogGroup(
      CatalogGroup g, List<CatalogGroupElementResponse> elements) {
    return new CatalogGroupResponse(
        g.id().toString(),
        g.name(),
        g.description(),
        g.status(),
        ts(g.createdAt()),
        ts(g.updatedAt()),
        elements);
  }

  public static CatalogGroupElementResponse toCatalogGroupElement(CatalogGroupElement e) {
    return new CatalogGroupElementResponse(
        e.id().toString(),
        e.groupId().toString(),
        e.elementName(),
        e.dataType(),
        e.required(),
        e.defaultVal(),
        e.sortOrder(),
        ts(e.createdAt()));
  }

  public static CatalogAssignmentResponse toCatalogAssignment(VariantCatalogAssignment a) {
    return new CatalogAssignmentResponse(
        a.id().toString(),
        a.variantId().toString(),
        a.groupId().toString(),
        a.elementVals(),
        ts(a.createdAt()),
        ts(a.updatedAt()));
  }

  public static ContainerTypeResponse toContainerType(ContainerType c) {
    return new ContainerTypeResponse(
        c.id().toString(),
        c.code(),
        c.name(),
        c.description(),
        c.lengthMm(),
        c.widthMm(),
        c.heightMm(),
        c.maxWeightKg(),
        c.tareWeightKg(),
        c.maxUnits(),
        c.status(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
  }

  public static VariantContainerLinkResponse toVariantContainerLink(
      VariantContainerLink l, String containerTypeCode, String containerTypeName) {
    return new VariantContainerLinkResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.containerTypeId().toString(),
        containerTypeCode,
        containerTypeName,
        l.qtyPerContainer(),
        l.isPrimary(),
        ts(l.createdAt()));
  }

  public static ItemAttributeGroupFieldResponse toAttributeGroupField(ItemAttributeGroupField f) {
    return new ItemAttributeGroupFieldResponse(
        f.fieldCode(), f.label(), f.dataType(), f.required(), f.sortOrder());
  }

  public static ItemAttributeGroupResponse toAttributeGroup(
      ItemAttributeGroup g, List<ItemAttributeGroupFieldResponse> fields) {
    return new ItemAttributeGroupResponse(g.groupCode(), g.name(), g.description(), fields);
  }

  public static VariantAttributeGroupValuesResponse toVariantAttributeGroupValues(
      VariantAttributeGroupValues v) {
    return new VariantAttributeGroupValuesResponse(
        v.id().toString(),
        v.variantId().toString(),
        v.groupCode(),
        v.values(),
        ts(v.createdAt()),
        ts(v.updatedAt()));
  }

  // ── Gap #39: Category sets ─────────────────────────────────────────────────

  public static CategorySetResponse toCategorySet(CategorySet s) {
    return new CategorySetResponse(
        s.id().toString(),
        s.name(),
        s.description(),
        s.purpose(),
        s.defaultCatId() == null ? null : s.defaultCatId().toString(),
        s.controlled(),
        s.status(),
        ts(s.createdAt()),
        ts(s.updatedAt()));
  }

  public static CategorySetMemberResponse toCategorySetMember(CategorySetMember m) {
    return new CategorySetMemberResponse(
        m.id().toString(), m.setId().toString(), m.categoryId().toString(), ts(m.createdAt()));
  }

  public static VariantCategorySetAssignmentResponse toVariantCategorySetAssignment(
      VariantCategorySetAssignment a) {
    return new VariantCategorySetAssignmentResponse(
        a.id().toString(),
        a.variantId().toString(),
        a.setId().toString(),
        a.categoryId().toString(),
        ts(a.createdAt()),
        ts(a.updatedAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
