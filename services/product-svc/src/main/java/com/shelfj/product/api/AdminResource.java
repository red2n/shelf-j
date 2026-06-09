package com.shelfj.product.api;

import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.ConvertResult;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateRevisionRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.ItemRevisionResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.UomClassResponse;
import com.shelfj.product.dto.Dtos.UomDefinitionResponse;
import com.shelfj.product.dto.Dtos.UomItemConversionRequest;
import com.shelfj.product.dto.Dtos.UomItemConversionResponse;
import com.shelfj.product.dto.Dtos.UpdateBrandRequest;
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
