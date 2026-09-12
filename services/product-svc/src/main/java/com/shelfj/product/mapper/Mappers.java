package com.shelfj.product.mapper;

import com.shelfj.product.domain.Domain;
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
import com.shelfj.product.dto.Dtos.AgeRestrictionRuleResponse;
import com.shelfj.product.dto.Dtos.AllergenEntry;
import com.shelfj.product.dto.Dtos.AllergenResponse;
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
import com.shelfj.product.dto.Dtos.VariantComplianceResponse;
import com.shelfj.product.dto.Dtos.VariantContainerLinkResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
import com.shelfj.product.dto.Dtos.VariantScanResponse;
import java.time.Instant;
import java.util.List;

/** Entity → DTO conversion. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a brand to its wire form.
   *
   * @param b the brand to convert
   * @return its API representation
   */
  public static BrandResponse toBrand(Brand b) {
    return new BrandResponse(
        b.id().toString(), b.name(), b.status(), ts(b.createdAt()), ts(b.updatedAt()));
  }

  /**
   * Converts a category to its wire form.
   *
   * @param c the category to convert
   * @return its API representation
   */
  public static CategoryResponse toCategory(Category c) {
    return new CategoryResponse(
        c.id().toString(),
        c.parentId() == null ? null : c.parentId().toString(),
        c.name(),
        c.status(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
  }

  /**
   * Converts a product to its wire form.
   *
   * @param p the product to convert
   * @return its API representation
   */
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

  /**
   * Converts a variant to its wire form.
   *
   * @param v the variant to convert
   * @return its API representation
   */
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

  /**
   * Converts an allergen to its wire form.
   *
   * @param a the allergen to convert
   * @return its API representation
   */
  public static AllergenResponse toAllergen(Domain.Allergen a) {
    return new AllergenResponse(a.code(), a.name(), a.detail(), a.regulation());
  }

  /**
   * Converts an allergen entry to its wire form.
   *
   * @param a the allergen entry to convert
   * @return its API representation
   */
  public static AllergenEntry toAllergenEntry(Domain.VariantAllergen a) {
    return new AllergenEntry(a.allergenCode(), a.presence());
  }

  /**
   * Converts a compliance to its wire form.
   *
   * @param c the compliance to convert
   * @return its API representation
   */
  public static VariantComplianceResponse toCompliance(Domain.VariantCompliance c) {
    return new VariantComplianceResponse(
        c.variantId().toString(),
        c.countryOfOrigin(),
        c.originDetail(),
        c.restrictionCategory(),
        c.allergenStatus(),
        c.ingredients(),
        c.soldBy(),
        c.netContent(),
        c.netContentUom(),
        c.tareWeight(),
        c.catchWeight());
  }

  /**
   * Converts an age rule to its wire form.
   *
   * @param r the age rule to convert
   * @return its API representation
   */
  public static AgeRestrictionRuleResponse toAgeRule(Domain.AgeRestrictionRule r) {
    return new AgeRestrictionRuleResponse(
        r.country(), r.category(), r.minimumAge(), r.note(), r.tenantId() != null);
  }

  /**
   * Converts a variant scan to its wire form.
   *
   * @param v the variant
   * @param p the product
   * @return its API representation
   */
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

  /**
   * Converts an uom class to its wire form.
   *
   * @param c the uom class to convert
   * @return its API representation
   */
  public static UomClassResponse toUomClass(UomClass c) {
    return new UomClassResponse(c.code(), c.name());
  }

  /**
   * Converts an uom definition to its wire form.
   *
   * @param d the uom definition to convert
   * @return its API representation
   */
  public static UomDefinitionResponse toUomDefinition(UomDefinition d) {
    return new UomDefinitionResponse(d.code(), d.name(), d.classCode());
  }

  /**
   * Converts an uom item conversion to its wire form.
   *
   * @param c the uom item conversion to convert
   * @return its API representation
   */
  public static UomItemConversionResponse toUomItemConversion(UomItemConversion c) {
    return new UomItemConversionResponse(
        c.id().toString(), c.variantId().toString(), c.fromUom(), c.toUom(), c.factor());
  }

  /**
   * Converts a template to its wire form.
   *
   * @param t the template to convert
   * @return its API representation
   */
  public static ItemTemplateResponse toTemplate(ItemTemplate t) {
    return new ItemTemplateResponse(
        t.id().toString(),
        t.name(),
        t.description(),
        t.attributes(),
        t.status(),
        ts(t.createdAt()));
  }

  /**
   * Converts a template application to its wire form.
   *
   * @param a the template application to convert
   * @return its API representation
   */
  public static ItemTemplateApplicationResponse toTemplateApplication(ItemTemplateApplication a) {
    return new ItemTemplateApplicationResponse(
        a.id().toString(), a.variantId().toString(), a.templateId().toString(), ts(a.appliedAt()));
  }

  /**
   * Converts a cross reference to its wire form.
   *
   * @param x the cross reference to convert
   * @return its API representation
   */
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

  /**
   * Converts a relationship to its wire form.
   *
   * @param r the relationship to convert
   * @return its API representation
   */
  public static ItemRelationshipResponse toRelationship(ItemRelationship r) {
    return new ItemRelationshipResponse(
        r.id().toString(),
        r.variantId().toString(),
        r.relatedVariantId().toString(),
        r.relationshipType(),
        ts(r.createdAt()));
  }

  /**
   * Converts a revision to its wire form.
   *
   * @param r the revision to convert
   * @return its API representation
   */
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

  /**
   * Converts a catalog group to its wire form.
   *
   * @param g the group
   * @param elements its elements
   * @return its API representation
   */
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

  /**
   * Converts a catalog group element to its wire form.
   *
   * @param e the catalog group element to convert
   * @return its API representation
   */
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

  /**
   * Converts a catalog assignment to its wire form.
   *
   * @param a the catalog assignment to convert
   * @return its API representation
   */
  public static CatalogAssignmentResponse toCatalogAssignment(VariantCatalogAssignment a) {
    return new CatalogAssignmentResponse(
        a.id().toString(),
        a.variantId().toString(),
        a.groupId().toString(),
        a.elementVals(),
        ts(a.createdAt()),
        ts(a.updatedAt()));
  }

  /**
   * Converts a container type to its wire form.
   *
   * @param c the container type to convert
   * @return its API representation
   */
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

  /**
   * Converts a variant container link to its wire form.
   *
   * @param l the link
   * @param containerTypeCode the container type code
   * @param containerTypeName the container type name
   * @return its API representation
   */
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

  /**
   * Converts an attribute group field to its wire form.
   *
   * @param f the attribute group field to convert
   * @return its API representation
   */
  public static ItemAttributeGroupFieldResponse toAttributeGroupField(ItemAttributeGroupField f) {
    return new ItemAttributeGroupFieldResponse(
        f.fieldCode(), f.label(), f.dataType(), f.required(), f.sortOrder());
  }

  /**
   * Converts an attribute group to its wire form.
   *
   * @param g the group
   * @param fields its fields
   * @return its API representation
   */
  public static ItemAttributeGroupResponse toAttributeGroup(
      ItemAttributeGroup g, List<ItemAttributeGroupFieldResponse> fields) {
    return new ItemAttributeGroupResponse(g.groupCode(), g.name(), g.description(), fields);
  }

  /**
   * Converts a variant attribute group values to its wire form.
   *
   * @param v the variant
   * @return its API representation
   */
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

  /**
   * Converts a category set to its wire form.
   *
   * @param s the category set to convert
   * @return its API representation
   */
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

  /**
   * Converts a category set member to its wire form.
   *
   * @param m the category set member to convert
   * @return its API representation
   */
  public static CategorySetMemberResponse toCategorySetMember(CategorySetMember m) {
    return new CategorySetMemberResponse(
        m.id().toString(), m.setId().toString(), m.categoryId().toString(), ts(m.createdAt()));
  }

  /**
   * Converts a variant category set assignment to its wire form.
   *
   * @param a the variant category set assignment to convert
   * @return its API representation
   */
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
