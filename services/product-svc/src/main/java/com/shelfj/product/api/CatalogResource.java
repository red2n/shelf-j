package com.shelfj.product.api;

import java.util.List;
import java.util.UUID;
import com.shelfj.product.dto.Dtos.ProductResponse;
import com.shelfj.product.dto.Dtos.VariantResponse;
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

/**
 * Public storefront catalog. The tenant (which business's storefront) comes from {@code X-Tenant-Id}, which the
 * gateway resolves from the storefront's domain/subdomain (multi-tenant SaaS standard). Only ACTIVE products show;
 * the online list returns only {@code sellable_online} products.
 */
@Path("/catalog")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

    @Inject ProductService service;
    @Inject TenantContext ctx;

    @GET
    @Path("/products")
    public ApiResponse<List<ProductResponse>> list(@QueryParam("category") String category,
                                                   @QueryParam("limit") Integer limit) {
        UUID tenantId = requireTenant();
        UUID categoryId = parseOptional(category);
        int clamped = Cursor.clampLimit(limit);
        List<ProductResponse> items = service.listProducts(tenantId, categoryId, true, clamped).stream()
                .map(Mappers::toProduct).toList();
        return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
    }

    @GET
    @Path("/products/{id}")
    public ApiResponse<ProductResponse> get(@PathParam("id") UUID id) {
        return ApiResponse.ok(Mappers.toProduct(service.getProduct(requireTenant(), id)));
    }

    @GET
    @Path("/products/{id}/variants")
    public ApiResponse<List<VariantResponse>> variants(@PathParam("id") UUID id) {
        var items = service.listVariants(requireTenant(), id).stream().map(Mappers::toVariant).toList();
        return ApiResponse.ok(items);
    }

    private UUID requireTenant() {
        if (ctx.tenantId() == null) {
            throw ApiException.badRequest("NO_STOREFRONT", "Storefront tenant not resolved (X-Tenant-Id)");
        }
        return ctx.tenantId();
    }

    private static UUID parseOptional(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CATEGORY", "category must be a UUID");
        }
    }
}
