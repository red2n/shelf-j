package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AvailabilityResponse;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

/**
 * Public storefront availability. Returns a per-variant in-stock flag for a store so the guest shop
 * can show "Available / Out of stock" when a store hides prices. No quantities are exposed. Tenant
 * comes from {@code X-Tenant-Id} (gateway storefront whitelist); read-only, no identity required.
 */
@Path("/inventory/availability")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StorefrontResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @GET
  public ApiResponse<List<AvailabilityResponse>> availability(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = null;
    if (store != null && !store.isBlank()) {
      try {
        storeId = UUID.fromString(store.trim());
      } catch (IllegalArgumentException e) {
        throw ApiException.badRequest("INVALID_STORE", "store must be a UUID");
      }
    }
    List<AvailabilityResponse> items =
        service.levels(tenantId, storeId).stream()
            .map(
                l ->
                    new AvailabilityResponse(
                        l.variantId().toString(),
                        l.available() != null && l.available().signum() > 0))
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }
}
