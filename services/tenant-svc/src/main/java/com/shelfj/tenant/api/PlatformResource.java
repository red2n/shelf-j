package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.PatchStatusRequest;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
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
import jakarta.ws.rs.QueryParam;
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

  /** Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  @GET
  @Path("/tenants")
  public ApiResponse<List<TenantResponse>> listAllTenants(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    var page = service.listAllTenants(after, Cursor.clampLimit(limit));
    var tenants = page.items().stream().map(Mappers::toTenant).toList();
    return ApiResponse.ok(tenants, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
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
