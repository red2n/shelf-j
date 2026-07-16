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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Pay-in / pay-out (petty cash movements) and daily Z-report settlement. MANAGER or above only —
 * these are reconciliation-level operations.
 */
@Path("/admin/cash")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cash Movements")
public class CashMovementResource {

  @Inject CashMovementService svc;
  @Inject TenantContext ctx;

  /** Record a pay-in or pay-out against an open till session. */
  @Operation(
      summary = "Record a pay-in or pay-out",
      description =
          "Petty cash movement against an open till session. direction must be PAY_IN or PAY_OUT."
              + " Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "201", description = "Cash movement recorded")
  @APIResponse(responseCode = "400", description = "Invalid direction")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @POST
  @Path("/movements")
  public Response recordMovement(
      @HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CashMovementRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID recordedBy = ctx.userId();
    var movement = svc.recordMovement(tenantId, recordedBy, req, ctx, idempotencyKey);
    return Response.status(201)
        .entity(ApiResponse.ok(movement, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** List movements for a till session. */
  @Operation(
      summary = "List cash movements",
      description =
          "All pay-in/pay-out movements recorded for the given till session. Requires"
              + " MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "Movements for the till session")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @GET
  @Path("/movements")
  public ApiResponse<?> listMovements(@QueryParam("tillSessionId") String tillSessionId) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    UUID sessionId = UUID.fromString(tillSessionId);
    return ApiResponse.ok(
        svc.listMovements(tenantId, sessionId), ApiResponse.Meta.of(ctx.requestId()));
  }

  /** Generate (or retrieve) the daily Z-report for a store. */
  @Operation(
      summary = "Generate the daily Z-report",
      description =
          "Generates (or retrieves an existing) end-of-day Z-report for a store and business date."
              + " Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "201", description = "Z-report generated")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @POST
  @Path("/z-report")
  public Response generateZReport(GenerateZReportRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID generatedBy = ctx.userId();
    var report = svc.generateZReport(tenantId, generatedBy, req, ctx);
    return Response.status(201)
        .entity(ApiResponse.ok(report, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** Retrieve an existing Z-report by store + date. */
  @Operation(
      summary = "Get the Z-report for a store and date",
      description = "Requires MANAGER or OWNER.")
  @APIResponse(responseCode = "200", description = "Z-report found")
  @APIResponse(responseCode = "403", description = "Caller lacks a manager/owner role")
  @APIResponse(responseCode = "404", description = "No Z-report for that store and date")
  @GET
  @Path("/z-report")
  public ApiResponse<?> getZReport(
      @QueryParam("storeId") String storeId, @QueryParam("businessDate") String businessDate) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        svc.getZReport(tenantId, UUID.fromString(storeId), businessDate, ctx),
        ApiResponse.Meta.of(ctx.requestId()));
  }
}
