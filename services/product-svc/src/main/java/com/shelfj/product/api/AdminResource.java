package com.shelfj.product.api;

import com.shelfj.product.dto.Dtos.AssignCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.BulkImportRequest;
import com.shelfj.product.dto.Dtos.BulkImportResult;
import com.shelfj.product.dto.Dtos.CatalogAssignmentResponse;
import com.shelfj.product.dto.Dtos.CatalogGroupResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.shelfj.product.dto.Dtos.CreateItemRelationshipRequest;
import com.shelfj.product.dto.Dtos.CreateItemTemplateRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateRevisionRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.ItemCrossReferenceResponse;
import com.shelfj.product.dto.Dtos.ItemRelationshipResponse;
import com.shelfj.product.dto.Dtos.ItemRevisionResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateApplicationResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.UomClassResponse;
import com.shelfj.product.dto.Dtos.UomDefinitionResponse;
import com.shelfj.product.dto.Dtos.UomItemConversionRequest;
import com.shelfj.product.dto.Dtos.UomItemConversionResponse;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
import com.shelfj.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.shelfj.product.dto.Dtos.UpdateCategoryRequest;
import com.shelfj.product.dto.Dtos.UpdateProductRequest;
import com.shelfj.product.dto.Dtos.UpdateVariantRequest;
import com.shelfj.product.dto.Dtos.VariantResponse;
import com.shelfj.product.mapper.Mappers;
import com.shelfj.product.service.ProductService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Admin catalog CRUD. Tenant-scoped (tenantId from context). */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject ProductService service;
  @Inject TenantContext ctx;

  // ── brands ───────────────────────────────────────────────────────────────

  @POST
  @Path("/brands")
  public Response createBrand(CreateBrandRequest req) {
    Validations.validate(req);
    return created(Mappers.toBrand(service.createBrand(ctx.requireTenantId(), req)));
  }

  @GET
  @Path("/brands")
  public ApiResponse<List<BrandResponse>> listBrands() {
    return ApiResponse.ok(
        service.listBrands(ctx.requireTenantId()).stream().map(Mappers::toBrand).toList());
  }

  @GET
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> getBrand(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toBrand(service.getBrand(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> updateBrand(@PathParam("id") UUID id, UpdateBrandRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toBrand(service.renameBrand(ctx.requireTenantId(), id, req)));
  }

  @DELETE
  @Path("/brands/{id}")
  public ApiResponse<BrandResponse> deactivateBrand(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toBrand(service.deactivateBrand(ctx.requireTenantId(), id)));
  }

  // ── categories ───────────────────────────────────────────────────────────

  @POST
  @Path("/categories")
  public Response createCategory(CreateCategoryRequest req) {
    Validations.validate(req);
    return created(Mappers.toCategory(service.createCategory(ctx.requireTenantId(), req)));
  }

  @GET
  @Path("/categories")
  public ApiResponse<List<CategoryResponse>> listCategories() {
    return ApiResponse.ok(
        service.listCategories(ctx.requireTenantId()).stream().map(Mappers::toCategory).toList());
  }

  @GET
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> getCategory(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toCategory(service.getCategory(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> updateCategory(
      @PathParam("id") UUID id, UpdateCategoryRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toCategory(service.updateCategory(ctx.requireTenantId(), id, req)));
  }

  @DELETE
  @Path("/categories/{id}")
  public ApiResponse<CategoryResponse> deactivateCategory(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toCategory(service.deactivateCategory(ctx.requireTenantId(), id)));
  }

  // ── products ─────────────────────────────────────────────────────────────

  @POST
  @Path("/products")
  public Response createProduct(CreateProductRequest req) {
    Validations.validate(req);
    return created(Mappers.toProduct(service.createProduct(ctx.requireTenantId(), req)));
  }

  /** Admin list — returns all statuses; optional ?status= and ?category= filters. */
  @GET
  @Path("/products")
  public ApiResponse<List<ProductResponse>> listProductsAdmin(
      @QueryParam("category") String category,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID categoryId = parseOptional(category, "category");
    int clamped = Cursor.clampLimit(limit);
    return ApiResponse.ok(
        service.listProductsAdmin(tenantId, categoryId, status, clamped).stream()
            .map(Mappers::toProduct)
            .toList());
  }

  @GET
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> getProduct(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toProduct(service.getProduct(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> updateProduct(
      @PathParam("id") UUID id, UpdateProductRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toProduct(service.updateProduct(ctx.requireTenantId(), id, req)));
  }

  @DELETE
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> delistProduct(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toProduct(service.delistProduct(ctx.requireTenantId(), id)));
  }

  // ── variants ─────────────────────────────────────────────────────────────

  @POST
  @Path("/products/{id}/variants")
  public Response createVariant(@PathParam("id") UUID productId, CreateVariantRequest req) {
    Validations.validate(req);
    return created(Mappers.toVariant(service.createVariant(ctx.requireTenantId(), productId, req)));
  }

  @GET
  @Path("/products/{id}/variants")
  public ApiResponse<List<VariantResponse>> listVariants(@PathParam("id") UUID productId) {
    return ApiResponse.ok(
        service.listVariants(ctx.requireTenantId(), productId).stream()
            .map(Mappers::toVariant)
            .toList());
  }

  @GET
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> getVariant(
      @PathParam("id") UUID productId, @PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(Mappers.toVariant(service.getVariant(ctx.requireTenantId(), variantId)));
  }

  @PUT
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> updateVariant(
      @PathParam("id") UUID productId,
      @PathParam("variantId") UUID variantId,
      UpdateVariantRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toVariant(service.updateVariant(ctx.requireTenantId(), productId, variantId, req)));
  }

  @DELETE
  @Path("/products/{id}/variants/{variantId}")
  public ApiResponse<VariantResponse> delistVariant(
      @PathParam("id") UUID productId, @PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(
        Mappers.toVariant(service.delistVariant(ctx.requireTenantId(), productId, variantId)));
  }

  // ── UOM ──────────────────────────────────────────────────────────────────

  @GET
  @Path("/uom/classes")
  public ApiResponse<List<UomClassResponse>> listUomClasses() {
    return ApiResponse.ok(service.listUomClasses().stream().map(Mappers::toUomClass).toList());
  }

  @GET
  @Path("/uom/units")
  public ApiResponse<List<UomDefinitionResponse>> listUomUnits(
      @QueryParam("class") String classCode) {
    return ApiResponse.ok(
        service.listUomDefinitions(classCode).stream().map(Mappers::toUomDefinition).toList());
  }

  @GET
  @Path("/uom/convert")
  public ApiResponse<ConvertResult> convertUom(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("qty") BigDecimal qty,
      @QueryParam("variant") String variantId) {
    if (from == null || to == null || qty == null) {
      throw new com.shelfj.web.ApiException(
          400, "MISSING_PARAM", "from, to, and qty are required", List.of(), null);
    }
    UUID variantUuid = parseOptional(variantId, "variant");
    UUID tenantId = variantUuid != null ? ctx.requireTenantId() : null;
    return ApiResponse.ok(
        service.convert(
            tenantId,
            variantUuid,
            from.toUpperCase(Locale.ROOT),
            to.toUpperCase(Locale.ROOT),
            qty));
  }

  @POST
  @Path("/uom/item-conversions")
  public Response upsertItemConversion(UomItemConversionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID variantId = UUID.fromString(req.variantId());
    return Response.status(Response.Status.OK)
        .entity(
            ApiResponse.ok(
                Mappers.toUomItemConversion(
                    service.upsertItemConversion(
                        tenantId,
                        variantId,
                        req.fromUom().toUpperCase(Locale.ROOT),
                        req.toUom().toUpperCase(Locale.ROOT),
                        req.factor()))))
        .build();
  }

  @GET
  @Path("/uom/item-conversions")
  public ApiResponse<List<UomItemConversionResponse>> listItemConversions(
      @QueryParam("variant") String variantId) {
    UUID tenantId = ctx.requireTenantId();
    UUID variantUuid = parseOptional(variantId, "variant");
    return ApiResponse.ok(
        service.listItemConversions(tenantId, variantUuid).stream()
            .map(Mappers::toUomItemConversion)
            .toList());
  }

  @DELETE
  @Path("/uom/item-conversions/{id}")
  public Response deleteItemConversion(@PathParam("id") UUID id) {
    boolean deleted = service.deleteItemConversion(ctx.requireTenantId(), id);
    if (!deleted) {
      throw new com.shelfj.web.ApiException(
          404, "CONVERSION_NOT_FOUND", "Item conversion not found", List.of(), null);
    }
    return Response.noContent().build();
  }

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  @POST
  @Path("/item-templates")
  public Response createTemplate(CreateItemTemplateRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toTemplate(
            service.createTemplate(
                tenantId, req.name().trim(), req.description(), req.attributes())));
  }

  @GET
  @Path("/item-templates")
  public ApiResponse<List<ItemTemplateResponse>> listTemplates() {
    return ApiResponse.ok(
        service.listTemplates(ctx.requireTenantId()).stream().map(Mappers::toTemplate).toList());
  }

  @GET
  @Path("/item-templates/{id}")
  public ApiResponse<ItemTemplateResponse> getTemplate(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toTemplate(service.getTemplate(ctx.requireTenantId(), id)));
  }

  @DELETE
  @Path("/item-templates/{id}")
  public ApiResponse<ItemTemplateResponse> deactivateTemplate(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toTemplate(service.deactivateTemplate(ctx.requireTenantId(), id)));
  }

  @POST
  @Path("/item-templates/{id}/apply/{variantId}")
  public ApiResponse<ItemTemplateApplicationResponse> applyTemplate(
      @PathParam("id") UUID templateId, @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toTemplateApplication(service.applyTemplate(tenantId, variantId, templateId)));
  }

  // ── Supplier / Customer Cross-References (Gap #33) ───────────────────────

  @POST
  @Path("/products/variants/{variantId}/cross-references")
  public Response createCrossReference(
      @PathParam("variantId") UUID variantId, CreateItemCrossReferenceRequest req) {
    Validations.validate(req);
    return created(
        Mappers.toCrossReference(
            service.createCrossReference(ctx.requireTenantId(), variantId, req)));
  }

  @GET
  @Path("/products/variants/{variantId}/cross-references")
  public ApiResponse<List<ItemCrossReferenceResponse>> listCrossReferences(
      @PathParam("variantId") UUID variantId, @QueryParam("partyType") String partyType) {
    return ApiResponse.ok(
        service.listCrossReferences(ctx.requireTenantId(), variantId, partyType).stream()
            .map(Mappers::toCrossReference)
            .toList());
  }

  @DELETE
  @Path("/products/variants/{variantId}/cross-references/{id}")
  public Response deleteCrossReference(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.deleteCrossReference(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  // ── Item Relationships (Gap #32) ─────────────────────────────────────────

  @POST
  @Path("/products/variants/{variantId}/relationships")
  public Response createRelationship(
      @PathParam("variantId") UUID variantId, CreateItemRelationshipRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(Mappers.toRelationship(service.createRelationship(tenantId, variantId, req)));
  }

  @GET
  @Path("/products/variants/{variantId}/relationships")
  public ApiResponse<List<ItemRelationshipResponse>> listRelationships(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listRelationships(tenantId, variantId).stream()
            .map(Mappers::toRelationship)
            .toList());
  }

  @DELETE
  @Path("/products/variants/{variantId}/relationships/{id}")
  public Response deleteRelationship(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.deleteRelationship(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  @POST
  @Path("/products/variants/{variantId}/revisions")
  public Response createRevision(
      @PathParam("variantId") UUID variantId, CreateRevisionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    java.time.LocalDate effectiveDate;
    try {
      effectiveDate = java.time.LocalDate.parse(req.effectiveDate());
    } catch (java.time.format.DateTimeParseException e) {
      throw new com.shelfj.web.ApiException(
          400, "INVALID_DATE", "effectiveDate must be ISO date (yyyy-MM-dd)", List.of(), e);
    }
    var rev =
        service.createRevision(
            tenantId, variantId, req.revision(), req.description(), effectiveDate);
    return created(Mappers.toRevision(rev));
  }

  @GET
  @Path("/products/variants/{variantId}/revisions")
  public ApiResponse<List<ItemRevisionResponse>> listRevisions(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listRevisions(tenantId, variantId).stream().map(Mappers::toRevision).toList());
  }

  @GET
  @Path("/products/variants/{variantId}/revisions/current")
  public ApiResponse<ItemRevisionResponse> currentRevision(@PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toRevision(service.currentRevision(tenantId, variantId)));
  }

  @GET
  @Path("/products/variants/{variantId}/revisions/{id}")
  public ApiResponse<ItemRevisionResponse> getRevision(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toRevision(service.getRevision(tenantId, id)));
  }

  // ── Bulk Import ──────────────────────────────────────────────────────────

  /**
   * Import categories and products+variants in one call.
   *
   * <p>Body: { "categories": [...], "products": [...] }
   *
   * <p>Each category: { "name": "Electronics", "parentName": null } Each product: { "name": "...",
   * "categoryName": "Electronics", "brandName": "Apple", "sellableOnline": true, "sellablePos":
   * true, "variants": [{ "sku": "SKU-001", "barcode": "...", "unit": "EA" }] }
   *
   * <p>Duplicate categories are skipped. Duplicate SKUs return an error entry but the rest
   * continue. Always returns 200 with a result summary and any per-row errors.
   */
  @POST
  @Path("/import")
  public ApiResponse<BulkImportResult> bulkImport(BulkImportRequest req) {
    if (req == null) {
      throw new com.shelfj.web.ApiException(
          400, "INVALID_BODY", "request body required", List.of(), null);
    }
    return ApiResponse.ok(service.bulkImport(ctx.requireTenantId(), req));
  }

  // ── Catalog Groups (Gap #35) ─────────────────────────────────────────────

  @POST
  @Path("/catalog-groups")
  public Response createCatalogGroup(CreateCatalogGroupRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var group = service.createCatalogGroup(tenantId, req);
    return created(Mappers.toCatalogGroup(group, List.of()));
  }

  @GET
  @Path("/catalog-groups")
  public ApiResponse<List<CatalogGroupResponse>> listCatalogGroups() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCatalogGroups(tenantId).stream()
            .map(
                g ->
                    Mappers.toCatalogGroup(
                        g,
                        service.listCatalogGroupElements(tenantId, g.id()).stream()
                            .map(Mappers::toCatalogGroupElement)
                            .toList()))
            .toList());
  }

  @GET
  @Path("/catalog-groups/{id}")
  public ApiResponse<CatalogGroupResponse> getCatalogGroup(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var group = service.getCatalogGroup(tenantId, id);
    var elements =
        service.listCatalogGroupElements(tenantId, id).stream()
            .map(Mappers::toCatalogGroupElement)
            .toList();
    return ApiResponse.ok(Mappers.toCatalogGroup(group, elements));
  }

  @DELETE
  @Path("/catalog-groups/{id}")
  public Response deactivateCatalogGroup(@PathParam("id") UUID id) {
    service.deactivateCatalogGroup(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  @POST
  @Path("/catalog-groups/{groupId}/elements")
  public Response createCatalogGroupElement(
      @PathParam("groupId") UUID groupId, CreateCatalogGroupElementRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toCatalogGroupElement(service.createCatalogGroupElement(tenantId, groupId, req)));
  }

  @DELETE
  @Path("/catalog-groups/{groupId}/elements/{elementId}")
  public Response deleteCatalogGroupElement(
      @PathParam("groupId") UUID groupId, @PathParam("elementId") UUID elementId) {
    service.deleteCatalogGroupElement(ctx.requireTenantId(), elementId);
    return Response.noContent().build();
  }

  @POST
  @Path("/products/variants/{variantId}/catalog-assignment")
  public Response assignCatalogGroup(
      @PathParam("variantId") UUID variantId, AssignCatalogGroupRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return created(
        Mappers.toCatalogAssignment(service.assignCatalogGroup(tenantId, variantId, req)));
  }

  @GET
  @Path("/products/variants/{variantId}/catalog-assignment")
  public ApiResponse<CatalogAssignmentResponse> getCatalogAssignment(
      @PathParam("variantId") UUID variantId) {
    return ApiResponse.ok(
        Mappers.toCatalogAssignment(
            service.getCatalogAssignment(ctx.requireTenantId(), variantId)));
  }

  @PUT
  @Path("/products/variants/{variantId}/catalog-assignment")
  public ApiResponse<CatalogAssignmentResponse> updateCatalogAssignment(
      @PathParam("variantId") UUID variantId, UpdateCatalogAssignmentRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toCatalogAssignment(service.updateCatalogAssignment(tenantId, variantId, req)));
  }

  @DELETE
  @Path("/products/variants/{variantId}/catalog-assignment")
  public Response deleteCatalogAssignment(@PathParam("variantId") UUID variantId) {
    service.deleteCatalogAssignment(ctx.requireTenantId(), variantId);
    return Response.noContent().build();
  }

  // ─────────────────────────────────────────────────────────────────── utils

  private static Response created(Object body) {
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(body)).build();
  }

  private static UUID parseOptional(String s, String field) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new com.shelfj.web.ApiException(
          400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }
}
