package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.SwitchingDtos.StatusResponse;
import com.shelfj.tenant.dto.SwitchingDtos.SweepResponse;
import com.shelfj.tenant.mapper.SwitchingMappers;
import com.shelfj.tenant.service.SwitchingService;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /platform/tenants/switching}: the platform's view of businesses leaving, and the sweep
 * that starts every erasure due, run now instead of waiting for the hourly one (21.14).
 */
@Path("/platform/tenants/switching")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Platform")
public class SwitchingPlatformResource {

  @Inject SwitchingService service;
  @Inject TenantContext ctx;

  @Operation(summary = "Where a business's leaving stands")
  @GET
  @Path("/{tenantId}")
  public ApiResponse<StatusResponse> status(@PathParam("tenantId") UUID tenantId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        SwitchingMappers.toStatus(service.requireStatus(tenantId)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Start every erasure due",
      description =
          "Each business whose erasure has fallen due is made inactive and every service told to"
              + " erase its data. Runs hourly on its own; safe to run again.")
  @POST
  @Path("/sweep")
  public ApiResponse<SweepResponse> sweep() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(new SweepResponse(service.sweep()), ApiResponse.Meta.of(ctx.requestId()));
  }
}
