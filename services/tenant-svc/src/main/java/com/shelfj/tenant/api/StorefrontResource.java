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

/**
 * Public storefront config. The guest online shop needs to know per-store display rules (e.g.
 * whether to show prices). Tenant comes from {@code X-Tenant-Id}, which the gateway resolves from
 * the storefront's domain (here, the {@code X-Storefront-Tenant} dev seam). Read-only, no identity
 * required — reachable via the gateway's storefront whitelist.
 */
@Path("/storefront")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StorefrontResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

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
    return ApiResponse.ok(
        new StorefrontConfigResponse(s.id().toString(), s.name(), s.status(), s.showPrices()));
  }

  /**
   * Whether this tenant may currently transact — the gateway calls this (cached) to gate storefront
   * browsing and checkout, so a deactivated business's online shop stops serving. Tenant comes from
   * {@code X-Tenant-Id} (gateway sets it from the storefront domain/header).
   */
  @GET
  @Path("/active")
  public ApiResponse<TenantActiveResponse> active() {
    UUID tenantId = ctx.requireTenantId();
    var t = service.getTenant(tenantId);
    return ApiResponse.ok(new TenantActiveResponse("ACTIVE".equalsIgnoreCase(t.status())));
  }

  /** Minimal active-flag projection for the gateway's storefront suspension gate. */
  public record TenantActiveResponse(boolean active) {}

  /** Active stores for the tenant — powers the storefront's store switcher. */
  @GET
  @Path("/stores")
  public ApiResponse<List<StorefrontConfigResponse>> stores() {
    UUID tenantId = ctx.requireTenantId();
    var items =
        service.listStores(tenantId).stream()
            .filter(s -> "ACTIVE".equalsIgnoreCase(s.status()))
            .map(
                s ->
                    new StorefrontConfigResponse(
                        s.id().toString(), s.name(), s.status(), s.showPrices()))
            .toList();
    return ApiResponse.ok(items);
  }
}
