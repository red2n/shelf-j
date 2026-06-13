package com.shelfj.payment.api;

import com.shelfj.payment.dto.Dtos.CashMovementRequest;
import com.shelfj.payment.dto.Dtos.GenerateZReportRequest;
import com.shelfj.payment.service.CashMovementService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Pay-in / pay-out (petty cash movements) and daily Z-report settlement. MANAGER or above only —
 * these are reconciliation-level operations.
 */
@Path("/admin/cash")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CashMovementResource {

  @Inject CashMovementService svc;
  @Inject TenantContext ctx;

  /** Record a pay-in or pay-out against an open till session. */
  @POST
  @Path("/movements")
  public Response recordMovement(CashMovementRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER", "PLATFORM_ADMIN");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID recordedBy = ctx.userId();
    var movement = svc.recordMovement(tenantId, recordedBy, req);
    return Response.status(201)
        .entity(ApiResponse.ok(movement, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** List movements for a till session. */
  @GET
  @Path("/movements")
  public ApiResponse<?> listMovements(@QueryParam("tillSessionId") String tillSessionId) {
    ctx.requireAnyRole("MANAGER", "OWNER", "PLATFORM_ADMIN");
    UUID tenantId = ctx.requireTenantId();
    UUID sessionId = UUID.fromString(tillSessionId);
    return ApiResponse.ok(
        svc.listMovements(tenantId, sessionId), ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Generate (or retrieve) the daily Z-report for a store. */
  @POST
  @Path("/z-report")
  public Response generateZReport(GenerateZReportRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER", "PLATFORM_ADMIN");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID generatedBy = ctx.userId();
    var report = svc.generateZReport(tenantId, generatedBy, req);
    return Response.status(201)
        .entity(ApiResponse.ok(report, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** Retrieve an existing Z-report by store + date. */
  @GET
  @Path("/z-report")
  public ApiResponse<?> getZReport(
      @QueryParam("storeId") String storeId, @QueryParam("businessDate") String businessDate) {
    ctx.requireAnyRole("MANAGER", "OWNER", "PLATFORM_ADMIN");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        svc.getZReport(tenantId, UUID.fromString(storeId), businessDate),
        ApiResponse.Meta.of(ctx.requestId()));
  }
}
