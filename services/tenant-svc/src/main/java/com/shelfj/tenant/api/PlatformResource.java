package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.PatchStatusRequest;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

/** Platform-admin endpoints — PLATFORM_ADMIN role required on every method. */
@Path("/platform")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlatformResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @GET
  @Path("/tenants")
  public ApiResponse<List<TenantResponse>> listAllTenants() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    var tenants = service.listAllTenants().stream().map(Mappers::toTenant).toList();
    return ApiResponse.ok(tenants, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PATCH
  @Path("/tenants/{tenantId}/status")
  public ApiResponse<TenantResponse> patchTenantStatus(
      @PathParam("tenantId") UUID tenantId, PatchStatusRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.patchTenantStatus(tenantId, req)));
  }
}
