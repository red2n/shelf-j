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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Platform-admin endpoints — PLATFORM_ADMIN role required on every method. */
@Path("/platform")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Platform")
public class PlatformResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List all tenants",
      description =
          "Cross-tenant platform-admin list. Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100."
              + " Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @GET
  @Path("/tenants")
  public ApiResponse<List<TenantResponse>> listAllTenants(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    var page = service.listAllTenants(after, Cursor.clampLimit(limit));
    var tenants = page.items().stream().map(Mappers::toTenant).toList();
    return ApiResponse.ok(tenants, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Suspend or reactivate a tenant",
      description =
          "Sets a tenant's status to ACTIVE or INACTIVE and publishes TenantStatusChanged so other"
              + " services (e.g. iam-svc locking out staff) can react. Requires PLATFORM_ADMIN.")
  @APIResponse(responseCode = "400", description = "status must be ACTIVE or INACTIVE")
  @APIResponse(responseCode = "403", description = "Caller is not a PLATFORM_ADMIN")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @PATCH
  @Path("/tenants/{tenantId}/status")
  public ApiResponse<TenantResponse> patchTenantStatus(
      @PathParam("tenantId") UUID tenantId, PatchStatusRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.patchTenantStatus(tenantId, req)));
  }
}
