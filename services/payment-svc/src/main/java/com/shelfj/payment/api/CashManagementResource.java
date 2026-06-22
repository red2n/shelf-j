package com.shelfj.payment.api;

import com.shelfj.payment.dto.Dtos.CloseTillRequest;
import com.shelfj.payment.dto.Dtos.OpenTillRequest;
import com.shelfj.payment.dto.Dtos.RecordCashDropRequest;
import com.shelfj.payment.service.CashManagementService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Cash management: till float, cash drops, X-report (read-only), Z-report (close).
 *
 * <p>Roles: CASHIER opens a till; MANAGER or above closes (Z-report) and records cash drops.
 */
@RequestScoped
@Path("/admin/cash/till-sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CashManagementResource {

  @Inject CashManagementService svc;
  @Inject TenantContext ctx;

  /** Open a till session (record opening float). CASHIER or above. */
  @POST
  public Response open(OpenTillRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID userId = ctx.userId();
    var session = svc.openTill(tenantId, userId, req, ctx);
    return Response.status(201).entity(ApiResponse.ok(session)).build();
  }

  /** Get current session status and float. */
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return Response.ok(ApiResponse.ok(svc.getSession(ctx.requireTenantId(), id, ctx))).build();
  }

  /** Record a cash drop (mid-shift safe drop). MANAGER or above. */
  @POST
  @Path("/{id}/drops")
  public Response drop(@PathParam("id") UUID id, RecordCashDropRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID userId = ctx.userId();
    var drop = svc.recordDrop(tenantId, id, userId, req.amount(), req.notes(), ctx);
    return Response.status(201).entity(ApiResponse.ok(drop)).build();
  }

  /** X-report: mid-day read-only snapshot. Does not close the till. MANAGER or above. */
  @GET
  @Path("/{id}/x-report")
  public Response xReport(@PathParam("id") UUID id) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    return Response.ok(ApiResponse.ok(svc.xReport(ctx.requireTenantId(), id, ctx))).build();
  }

  /** Z-report: end-of-day close. Requires counted cash amount. MANAGER or above. */
  @POST
  @Path("/{id}/close")
  public Response close(@PathParam("id") UUID id, CloseTillRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER");
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(svc.zReport(ctx.requireTenantId(), id, req, ctx))).build();
  }
}
