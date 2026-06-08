package com.shelfj.product.api;

import com.shelfj.product.dto.Dtos.BrandResponse;
import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.CreateBrandRequest;
import com.shelfj.product.dto.Dtos.CreateCategoryRequest;
import com.shelfj.product.dto.Dtos.CreateProductRequest;
import com.shelfj.product.dto.Dtos.CreateVariantRequest;
import com.shelfj.product.dto.Dtos.ProductResponse;
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
import java.util.List;
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
