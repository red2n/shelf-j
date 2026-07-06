package com.shelfj.product.api;

import com.shelfj.product.dto.Dtos.AddCategorySetMemberRequest;
import com.shelfj.product.dto.Dtos.AssignCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.AssignVariantCategorySetRequest;
import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.BulkImportRequest;
import com.shelfj.product.dto.Dtos.BulkImportResult;
import com.shelfj.product.dto.Dtos.CatalogAssignmentResponse;
import com.shelfj.product.dto.Dtos.CatalogGroupResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.CategorySetMemberResponse;
import com.shelfj.product.dto.Dtos.CategorySetResponse;
import com.shelfj.product.dto.Dtos.ContainerTypeResponse;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupElementRequest;
import com.shelfj.product.dto.Dtos.CreateCatalogGroupRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateCategorySetRequest;
import com.shelfj.product.dto.Dtos.CreateContainerTypeRequest;
import com.shelfj.product.dto.Dtos.CreateItemCrossReferenceRequest;
import com.shelfj.product.dto.Dtos.CreateItemRelationshipRequest;
import com.shelfj.product.dto.Dtos.CreateItemTemplateRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateRevisionRequest;
import com.shelfj.product.dto.Dtos.CreateVariantContainerLinkRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.ItemAttributeGroupResponse;
import com.shelfj.product.dto.Dtos.ItemCrossReferenceResponse;
import com.shelfj.product.dto.Dtos.ItemRelationshipResponse;
import com.shelfj.product.dto.Dtos.ItemRevisionResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateApplicationResponse;
import com.shelfj.product.dto.Dtos.ItemTemplateResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.ProductStoresRequest;
import com.shelfj.product.dto.Dtos.UomClassResponse;
import com.shelfj.product.dto.Dtos.UomDefinitionResponse;
import com.shelfj.product.dto.Dtos.UomItemConversionRequest;
import com.shelfj.product.dto.Dtos.UomItemConversionResponse;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
import com.shelfj.product.dto.Dtos.UpdateCatalogAssignmentRequest;
import com.shelfj.product.dto.Dtos.UpdateCategoryRequest;
import com.shelfj.product.dto.Dtos.UpdateCategorySetRequest;
import com.shelfj.product.dto.Dtos.UpdateContainerTypeRequest;
import com.shelfj.product.dto.Dtos.UpdateProductRequest;
import com.shelfj.product.dto.Dtos.UpdateVariantRequest;
import com.shelfj.product.dto.Dtos.UpsertVariantAttributeGroupRequest;
import com.shelfj.product.dto.Dtos.VariantAttributeGroupValuesResponse;
import com.shelfj.product.dto.Dtos.VariantCategorySetAssignmentResponse;
import com.shelfj.product.dto.Dtos.VariantContainerLinkResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
import com.shelfj.product.dto.Dtos.VariantScanResponse;
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

  /**
   * Admin list — returns all statuses; optional ?status= and ?category= filters.
   * ?after=<cursor>&limit=1-100 (default 20) for pagination — previously capped at one page with no
   * way to reach the rest of a tenant's catalog.
   */
  @GET
  @Path("/products")
  public ApiResponse<List<ProductResponse>> listProductsAdmin(
      @QueryParam("category") String category,
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID categoryId = parseOptional(category, "category");
    int clamped = Cursor.clampLimit(limit);
    var page = service.listProductsAdmin(tenantId, categoryId, status, after, clamped);
    return ApiResponse.ok(
        page.products().stream().map(Mappers::toProduct).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
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

  // ── product image ──────────────────────────────────────────────────────────

  /**
   * Upload/replace the product's primary image. Raw body (not multipart): the admin app PUTs the
   * bytes with the image's own Content-Type (image/jpeg | image/png | image/webp), max 512 KB.
   */
  @PUT
  @Path("/products/{id}/image")
  @Consumes({"image/jpeg", "image/png", "image/webp"})
  public ApiResponse<String> uploadProductImage(
      @PathParam("id") UUID id,
      @jakarta.ws.rs.HeaderParam("Content-Type") String contentType,
      byte[] body) {
    service.uploadProductImage(ctx.requireTenantId(), id, contentType, body);
    return ApiResponse.ok("uploaded");
  }

  @DELETE
  @Path("/products/{id}/image")
  public ApiResponse<String> deleteProductImage(@PathParam("id") UUID id) {
    service.deleteProductImage(ctx.requireTenantId(), id);
    return ApiResponse.ok("deleted");
  }

  // ── per-store assortment ───────────────────────────────────────────────────

  /** Store ids this product is sold at. Empty list = sold at all stores. */
  @GET
  @Path("/products/{id}/stores")
  public ApiResponse<List<String>> getProductStores(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        service.getProductStores(ctx.requireTenantId(), id).stream().map(UUID::toString).toList());
  }

  /** Replace the product's store assortment. Empty/absent list = sold at all stores. */
  @PUT
  @Path("/products/{id}/stores")
  public ApiResponse<List<String>> setProductStores(
      @PathParam("id") UUID id, ProductStoresRequest req) {
    List<UUID> ids =
        (req == null || req.storeIds() == null)
            ? List.of()
            : req.storeIds().stream().map(UUID::fromString).toList();
    service.setProductStores(ctx.requireTenantId(), id, ids);
    return ApiResponse.ok(ids.stream().map(UUID::toString).toList());
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

  /**
   * Batch-resolves variant ids to name + SKU + product context so admin screens (e.g. inventory)
   * can show human-readable labels instead of raw variant UUIDs. {@code ?ids=a,b,c} (max 200);
   * unknown ids are simply omitted from the result.
   */
  @GET
  @Path("/products/variants/resolve")
  public ApiResponse<List<VariantScanResponse>> resolveVariants(@QueryParam("ids") String ids) {
    UUID tenantId = ctx.requireTenantId();
    if (ids == null || ids.isBlank()) {
      return ApiResponse.ok(List.of());
    }
    List<UUID> idList =
        java.util.Arrays.stream(ids.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .limit(200)
            .map(s -> com.shelfj.web.Parsing.uuid(s, "ids"))
            .toList();
    return ApiResponse.ok(
        service.resolveVariants(tenantId, idList).stream()
            .map(vp -> Mappers.toVariantScan(vp.variant(), vp.product()))
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
    java.time.LocalDate effectiveDate =
        com.shelfj.web.Parsing.date(req.effectiveDate(), "effectiveDate");
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

  /**
   * Import a supplier catalogue CSV (GTBJ format). Body: JSON with {@code csv} (raw CSV text),
   * optional {@code mode} (ADD|REPLACE), optional {@code storeNameToId} map (store name → UUID
   * string, resolved client-side so this service never calls tenant-svc synchronously).
   */
  @POST
  @Path("/import/supplier-csv")
  public ApiResponse<BulkImportResult> importSupplierCsv(
      com.shelfj.product.dto.Dtos.SupplierCsvImportRequest req) {
    if (req == null || req.csv() == null || req.csv().isBlank()) {
      throw new com.shelfj.web.ApiException(
          400, "INVALID_BODY", "csv field is required", List.of(), null);
    }
    return ApiResponse.ok(
        service.importSupplierCsv(ctx.requireTenantId(), String.join(",", ctx.roles()), req));
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

  // ── Container Types (Gap #37) ────────────────────────────────────────────

  @POST
  @Path("/container-types")
  public Response createContainerType(CreateContainerTypeRequest req) {
    Validations.validate(req);
    return created(
        Mappers.toContainerType(service.createContainerType(ctx.requireTenantId(), req)));
  }

  @GET
  @Path("/container-types")
  public ApiResponse<List<ContainerTypeResponse>> listContainerTypes() {
    return ApiResponse.ok(
        service.listContainerTypes(ctx.requireTenantId()).stream()
            .map(Mappers::toContainerType)
            .toList());
  }

  @GET
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> getContainerType(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toContainerType(service.getContainerType(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> updateContainerType(
      @PathParam("id") UUID id, UpdateContainerTypeRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toContainerType(service.updateContainerType(ctx.requireTenantId(), id, req)));
  }

  @DELETE
  @Path("/container-types/{id}")
  public ApiResponse<ContainerTypeResponse> deactivateContainerType(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toContainerType(service.deactivateContainerType(ctx.requireTenantId(), id)));
  }

  @POST
  @Path("/products/variants/{variantId}/container-links")
  public Response createVariantContainerLink(
      @PathParam("variantId") UUID variantId, CreateVariantContainerLinkRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var link = service.createVariantContainerLink(tenantId, variantId, req);
    var ct = service.getContainerType(tenantId, link.containerTypeId());
    return created(Mappers.toVariantContainerLink(link, ct.code(), ct.name()));
  }

  @GET
  @Path("/products/variants/{variantId}/container-links")
  public ApiResponse<List<VariantContainerLinkResponse>> listVariantContainerLinks(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantContainerLinks(tenantId, variantId).stream()
            .map(
                l -> {
                  var ct = service.getContainerType(tenantId, l.containerTypeId());
                  return Mappers.toVariantContainerLink(l, ct.code(), ct.name());
                })
            .toList());
  }

  @DELETE
  @Path("/products/variants/{variantId}/container-links/{id}")
  public Response deleteVariantContainerLink(
      @PathParam("variantId") UUID variantId, @PathParam("id") UUID id) {
    service.deleteVariantContainerLink(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  // ── Item Attribute Groups (Gap #36) ─────────────────────────────────────

  @GET
  @Path("/attribute-groups")
  public ApiResponse<List<ItemAttributeGroupResponse>> listAttributeGroups() {
    return ApiResponse.ok(
        service.listAttributeGroups().stream()
            .map(
                g ->
                    Mappers.toAttributeGroup(
                        g,
                        service.listAttributeGroupFields(g.groupCode()).stream()
                            .map(Mappers::toAttributeGroupField)
                            .toList()))
            .toList());
  }

  @GET
  @Path("/attribute-groups/{groupCode}")
  public ApiResponse<ItemAttributeGroupResponse> getAttributeGroup(
      @PathParam("groupCode") String groupCode) {
    var group = service.getAttributeGroup(groupCode);
    var fields =
        service.listAttributeGroupFields(group.groupCode()).stream()
            .map(Mappers::toAttributeGroupField)
            .toList();
    return ApiResponse.ok(Mappers.toAttributeGroup(group, fields));
  }

  @PUT
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public ApiResponse<VariantAttributeGroupValuesResponse> upsertVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId,
      @PathParam("groupCode") String groupCode,
      UpsertVariantAttributeGroupRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toVariantAttributeGroupValues(
            service.upsertVariantAttributeGroupValues(
                tenantId, variantId, groupCode, req.values())));
  }

  @GET
  @Path("/products/variants/{variantId}/attribute-groups")
  public ApiResponse<List<VariantAttributeGroupValuesResponse>> listVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantAttributeGroupValues(tenantId, variantId).stream()
            .map(Mappers::toVariantAttributeGroupValues)
            .toList());
  }

  @GET
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public ApiResponse<VariantAttributeGroupValuesResponse> getVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId, @PathParam("groupCode") String groupCode) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toVariantAttributeGroupValues(
            service.getVariantAttributeGroupValues(tenantId, variantId, groupCode)));
  }

  @DELETE
  @Path("/products/variants/{variantId}/attribute-groups/{groupCode}")
  public Response deleteVariantAttributeGroupValues(
      @PathParam("variantId") UUID variantId, @PathParam("groupCode") String groupCode) {
    service.deleteVariantAttributeGroupValues(ctx.requireTenantId(), variantId, groupCode);
    return Response.noContent().build();
  }

  // ──────────────────────────────────────────────── category sets (Gap #39) ──

  @POST
  @Path("/category-sets")
  public Response createCategorySet(CreateCategorySetRequest req) {
    Validations.validate(req);
    var cs = service.createCategorySet(ctx.requireTenantId(), req);
    return created(Mappers.toCategorySet(cs));
  }

  @GET
  @Path("/category-sets")
  public ApiResponse<List<CategorySetResponse>> listCategorySets() {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCategorySets(tenantId).stream().map(Mappers::toCategorySet).toList());
  }

  @GET
  @Path("/category-sets/{id}")
  public ApiResponse<CategorySetResponse> getCategorySet(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toCategorySet(service.getCategorySet(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/category-sets/{id}")
  public ApiResponse<CategorySetResponse> updateCategorySet(
      @PathParam("id") UUID id, UpdateCategorySetRequest req) {
    return ApiResponse.ok(
        Mappers.toCategorySet(service.updateCategorySet(ctx.requireTenantId(), id, req)));
  }

  @DELETE
  @Path("/category-sets/{id}")
  public Response deleteCategorySet(@PathParam("id") UUID id) {
    service.deleteCategorySet(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  @POST
  @Path("/category-sets/{id}/members")
  public Response addCategorySetMember(
      @PathParam("id") UUID setId, AddCategorySetMemberRequest req) {
    var m = service.addCategorySetMember(ctx.requireTenantId(), setId, req);
    return created(Mappers.toCategorySetMember(m));
  }

  @GET
  @Path("/category-sets/{id}/members")
  public ApiResponse<List<CategorySetMemberResponse>> listCategorySetMembers(
      @PathParam("id") UUID setId) {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listCategorySetMembers(tenantId, setId).stream()
            .map(Mappers::toCategorySetMember)
            .toList());
  }

  @DELETE
  @Path("/category-sets/{id}/members/{categoryId}")
  public Response deleteCategorySetMember(
      @PathParam("id") UUID setId, @PathParam("categoryId") UUID categoryId) {
    service.deleteCategorySetMember(ctx.requireTenantId(), setId, categoryId);
    return Response.noContent().build();
  }

  @POST
  @Path("/products/variants/{variantId}/category-set-assignments")
  public Response assignVariantCategorySet(
      @PathParam("variantId") UUID variantId, AssignVariantCategorySetRequest req) {
    var a = service.assignVariantCategorySet(ctx.requireTenantId(), variantId, req);
    return created(Mappers.toVariantCategorySetAssignment(a));
  }

  @GET
  @Path("/products/variants/{variantId}/category-set-assignments")
  public ApiResponse<List<VariantCategorySetAssignmentResponse>> listVariantCategorySetAssignments(
      @PathParam("variantId") UUID variantId) {
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listVariantCategorySetAssignments(tenantId, variantId).stream()
            .map(Mappers::toVariantCategorySetAssignment)
            .toList());
  }

  @DELETE
  @Path("/products/variants/{variantId}/category-set-assignments/{setId}")
  public Response deleteVariantCategorySetAssignment(
      @PathParam("variantId") UUID variantId, @PathParam("setId") UUID setId) {
    service.deleteVariantCategorySetAssignment(ctx.requireTenantId(), variantId, setId);
    return Response.noContent().build();
  }

  // ─────────────────────────────────────────────────────────────────── utils

  private static Response created(Object body) {
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(body)).build();
  }

  private static UUID parseOptional(String s, String field) {
    return s == null || s.isBlank() ? null : com.shelfj.web.Parsing.uuid(s, field);
  }
}
