package com.shelfj.product.api;

import com.shelfj.product.dto.Dtos.CategoryResponse;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
import com.shelfj.product.dto.Dtos.VariantScanResponse;
import com.shelfj.product.mapper.Mappers;
import com.shelfj.product.service.ProductService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront catalog. The tenant (which business's storefront) comes from {@code
 * X-Tenant-Id}, which the gateway resolves from the storefront's domain/subdomain (multi-tenant
 * SaaS standard). Only ACTIVE products show; the online list returns only {@code sellable_online}
 * products.
 *
 * <p>Search params on {@code GET /catalog/products}: {@code ?q=} (name contains, case-insensitive),
 * {@code ?sku=} (exact SKU), {@code ?barcode=} (exact barcode). At least one of q/sku/barcode
 * routes to the search path; without them, the standard filtered list is returned.
 *
 * <p>POS barcode scan: {@code GET /catalog/variants/by-barcode/{code}} returns a {@link
 * VariantScanResponse} embedding product context so the terminal needs only one round-trip.
 */
@Path("/catalog")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Catalog")
public class CatalogResource {

  @Inject ProductService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Browse or search the public catalog",
      description =
          "Lists ACTIVE products; the online channel further filters to sellable_online, POS"
              + " channel to sellable_pos. At least one of q/sku/barcode routes to the search"
              + " path; otherwise the standard filtered list is returned. Tenant comes from"
              + " X-Tenant-Id.")
  @APIResponse(
      responseCode = "400",
      description = "storefront tenant not resolved, or store is not a UUID")
  @GET
  @Path("/products")
  public ApiResponse<List<ProductResponse>> list(
      @QueryParam("category") String category,
      @QueryParam("q") String q,
      @QueryParam("sku") String sku,
      @QueryParam("barcode") String barcode,
      @QueryParam("store") String store,
      @QueryParam("channel") String channel,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = requireTenant();
    int clamped = Cursor.clampLimit(limit);
    // When a store is given, only products assorted for that store (or sold everywhere) show.
    UUID storeId = parseOptionalUuid(store, "INVALID_STORE", "store must be a UUID");
    // channel=POS serves the in-store till (sellable_pos); default/ONLINE serves the storefront
    // (sellable_online). Catalog is cashier-reachable, so POS staff use this instead of /admin.
    boolean pos = "POS".equalsIgnoreCase(channel == null ? null : channel.trim());
    boolean onlineOnly = !pos;

    List<ProductResponse> items;
    if (q != null || sku != null || barcode != null) {
      String trimQ = blank(q) ? null : q.trim();
      String trimSku = blank(sku) ? null : sku.trim();
      String trimBarcode = blank(barcode) ? null : barcode.trim();
      // Search already returns all channels; narrow to POS only when the till asks.
      items =
          service
              .searchProducts(tenantId, trimQ, trimSku, trimBarcode, false, pos, storeId, clamped)
              .stream()
              .map(Mappers::toProduct)
              .toList();
    } else {
      UUID categoryId = parseOptionalUuid(category, "INVALID_CATEGORY", "category must be a UUID");
      items =
          service.listProducts(tenantId, categoryId, onlineOnly, pos, storeId, clamped).stream()
              .map(Mappers::toProduct)
              .toList();
    }
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Public category list for storefront browse-by-category. Tenant from {@code X-Tenant-Id}. */
  @Operation(
      summary = "List storefront categories",
      description = "Public category list for browse-by-category. Tenant from X-Tenant-Id.")
  @GET
  @Path("/categories")
  public ApiResponse<List<CategoryResponse>> categories() {
    var items = service.listCategories(requireTenant()).stream().map(Mappers::toCategory).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Get a storefront product by id")
  @APIResponse(responseCode = "404", description = "No such product for this tenant")
  @GET
  @Path("/products/{id}")
  public ApiResponse<ProductResponse> get(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toProduct(service.getProduct(requireTenant(), id)));
  }

  @Operation(summary = "List a product's variants")
  @GET
  @Path("/products/{id}/variants")
  public ApiResponse<List<VariantResponse>> variants(@PathParam("id") UUID id) {
    var items = service.listVariants(requireTenant(), id).stream().map(Mappers::toVariant).toList();
    return ApiResponse.ok(items);
  }

  /**
   * The product's primary image bytes (owner-uploaded). 404 when the product has no image — the
   * storefront/admin renders its colour-tile placeholder instead. Cached briefly so catalog pages
   * don't re-download on every visit but a replaced image shows up within a minute.
   */
  @Operation(
      summary = "Fetch a product's primary image",
      description =
          "Returns the owner-uploaded image bytes. 404 when the product has no image — the"
              + " caller should render a placeholder instead. Cached for 60 seconds.")
  @APIResponse(responseCode = "200", description = "Image bytes with their original Content-Type")
  @APIResponse(responseCode = "404", description = "Product has no image, or does not exist")
  @GET
  @Path("/products/{id}/image")
  public jakarta.ws.rs.core.Response image(@PathParam("id") UUID id) {
    var img = service.getProductImage(requireTenant(), id);
    return jakarta.ws.rs.core.Response.ok(img.bytes(), img.contentType())
        .header("Cache-Control", "public, max-age=60")
        .build();
  }

  /**
   * POS barcode-scan lookup. Returns the variant and its parent product in a single response so the
   * terminal does not need a second round-trip. Returns 404 when no active variant matches.
   */
  @Operation(
      summary = "Look up a variant by barcode",
      description =
          "POS barcode-scan lookup. Returns the variant and its parent product in a single"
              + " response so the terminal needs only one round-trip.")
  @APIResponse(responseCode = "400", description = "barcode is blank")
  @APIResponse(responseCode = "404", description = "No active variant found for this barcode")
  @GET
  @Path("/variants/by-barcode/{code}")
  public ApiResponse<VariantScanResponse> scanByBarcode(@PathParam("code") String code) {
    UUID tenantId = requireTenant();
    if (code == null || code.isBlank()) {
      throw ApiException.badRequest("INVALID_BARCODE", "barcode must not be blank");
    }
    var vp = service.findVariantByBarcode(tenantId, code.trim());
    return ApiResponse.ok(Mappers.toVariantScan(vp.variant(), vp.product()));
  }

  private UUID requireTenant() {
    if (ctx.tenantId() == null) {
      throw ApiException.badRequest(
          "NO_STOREFRONT", "Storefront tenant not resolved (X-Tenant-Id)");
    }
    return ctx.tenantId();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static UUID parseOptionalUuid(String s, String code, String message) {
    if (blank(s)) {
      return null;
    }
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, code, message, List.of(), e);
    }
  }
}
