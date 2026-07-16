package com.shelfj.tenant.api;

import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.dto.Dtos.StorefrontConfigResponse;
import com.shelfj.tenant.service.TenantService;
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
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public storefront config. The guest online shop needs to know per-store display rules (e.g.
 * whether to show prices). Tenant comes from {@code X-Tenant-Id}, which the gateway resolves from
 * the storefront's domain (here, the {@code X-Storefront-Tenant} dev seam). Read-only, no identity
 * required — reachable via the gateway's storefront whitelist.
 */
@Path("/storefront")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Storefront")
public class StorefrontResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Get a store's public storefront config",
      description =
          "Display rules for the guest online shop (price visibility, enabled payment methods,"
              + " address). No identity required.")
  @APIResponse(responseCode = "400", description = "store query parameter missing or not a UUID")
  @APIResponse(responseCode = "404", description = "No such store")
  @GET
  @Path("/config")
  public ApiResponse<StorefrontConfigResponse> config(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    if (store == null || store.isBlank()) {
      throw ApiException.badRequest("STORE_REQUIRED", "store query parameter is required");
    }
    UUID storeId;
    try {
      storeId = UUID.fromString(store.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_STORE", "store must be a UUID", java.util.List.of(), e);
    }
    Store s = service.getStore(tenantId, storeId);
    return ApiResponse.ok(StorefrontResource.toStorefrontConfig(s));
  }

  /**
   * Whether this tenant may currently transact — the gateway calls this (cached) to gate storefront
   * browsing and checkout, so a deactivated business's online shop stops serving. Tenant comes from
   * {@code X-Tenant-Id} (gateway sets it from the storefront domain/header).
   */
  @Operation(
      summary = "Check whether the tenant may currently transact",
      description =
          "Gateway calls this (cached) to gate storefront browsing and checkout, so a deactivated"
              + " business's online shop stops serving.")
  @GET
  @Path("/active")
  public ApiResponse<TenantActiveResponse> active() {
    UUID tenantId = ctx.requireTenantId();
    var t = service.getTenant(tenantId);
    return ApiResponse.ok(new TenantActiveResponse("ACTIVE".equalsIgnoreCase(t.status())));
  }

  /** Minimal active-flag projection for the gateway's storefront suspension gate. */
  @Schema(
      name = "TenantActiveResponse",
      description = "Minimal active-flag projection for the gateway's storefront suspension gate.")
  public record TenantActiveResponse(
      @Schema(description = "True if the tenant's status is ACTIVE.") boolean active) {}

  @Operation(
      summary = "List active stores for the tenant",
      description = "Powers the storefront's store switcher.")
  @GET
  @Path("/stores")
  public ApiResponse<List<StorefrontConfigResponse>> stores() {
    UUID tenantId = ctx.requireTenantId();
    List<StorefrontConfigResponse> items =
        service.listStores(tenantId).stream()
            .filter(s -> "ACTIVE".equalsIgnoreCase(s.status()))
            .map(StorefrontResource::toStorefrontConfig)
            .toList();
    return ApiResponse.ok(items);
  }

  private static StorefrontConfigResponse toStorefrontConfig(Store s) {
    return new StorefrontConfigResponse(
        s.id().toString(),
        s.name(),
        s.status(),
        s.showPrices(),
        com.shelfj.tenant.mapper.Mappers.paymentMethodsList(s.enabledPaymentMethods()),
        s.line1(),
        s.city(),
        s.country(),
        s.pincode(),
        null);
  }
}
