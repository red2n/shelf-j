package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.SecurityNoticeResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.SecurityIncidentService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Security notices a business has been sent (21.15). Under {@code /admin/}, so management only by
 * path: the owner or a manager answers for the business as controller of its customers' data.
 */
@Path("/admin/tenant/security-notices")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Security notices")
public class SecurityNoticeResource {

  @Inject SecurityIncidentService service;
  @Inject TenantContext ctx;

  @Operation(summary = "This business's security notices, newest first")
  @APIResponse(responseCode = "403", description = "Not a management role")
  @GET
  public ApiResponse<List<SecurityNoticeResponse>> list() {
    return ApiResponse.ok(
        service.notices(ctx.requireTenantId()).stream().map(Mappers::toSecurityNotice).toList());
  }

  @Operation(
      summary = "Acknowledge a security notice",
      description =
          "Records who confirmed they read it, once; acknowledging again returns the first.")
  @APIResponse(responseCode = "404", description = "This business has no such notice")
  @POST
  @Path("/{id}/acknowledge")
  public ApiResponse<SecurityNoticeResponse> acknowledge(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toSecurityNotice(
            service.acknowledge(ctx.requireTenantId(), id, ctx.requireUserId())));
  }
}
