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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront availability. Returns a per-variant in-stock flag for a store so the guest shop
 * can show "Available / Out of stock" when a store hides prices. No quantities are exposed. Tenant
 * comes from {@code X-Tenant-Id} (gateway storefront whitelist); read-only, no identity required.
 */
@Path("/inventory/availability")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront Availability")
public class StorefrontResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Gets per-variant availability for a store.
   *
   * <p>Public, read-only in-stock/out-of-stock flag per variant. No quantities are exposed.
   *
   * @param store the store (query parameter)
   * @throws com.shelfj.web.ApiException {@code 400} store must be a UUID
   */
  @Operation(
      summary = "Get per-variant availability for a store",
      description =
          "Public, read-only in-stock/out-of-stock flag per variant. No quantities are"
              + " exposed.")
  @APIResponse(responseCode = "400", description = "store must be a UUID")
  @GET
  public ApiResponse<List<AvailabilityResponse>> availability(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = null;
    if (store != null && !store.isBlank()) {
      try {
        storeId = UUID.fromString(store.trim());
      } catch (IllegalArgumentException e) {
        throw new ApiException(
            400, "INVALID_STORE", "store must be a UUID", java.util.List.of(), e);
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
