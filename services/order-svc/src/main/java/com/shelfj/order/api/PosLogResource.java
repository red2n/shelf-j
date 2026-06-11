package com.shelfj.order.api;

import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Gap #43 — POSLog / transaction journal. Append-only log of completed POS transactions. POST
 * /{orderId}/pos-log records the transaction journal for a fulfilled POS order.
 */
@RequestScoped
@Path("/admin/pos-log")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PosLogResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /** Record POSLog entry for a POS order (call after order is fulfilled). */
  @POST
  @Path("/orders/{orderId}")
  public Response record(@PathParam("orderId") UUID orderId) {
    UUID tenantId = ctx.requireTenantId();
    var entry = svc.recordPosLog(tenantId, orderId, ctx.userId());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(entry))).build();
  }

  @GET
  public Response list(@QueryParam("storeId") String storeId) {
    var entries =
        svc.listPosLog(ctx.requireTenantId(), storeId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(entries)).build();
  }

  @GET
  @Path("/orders/{orderId}")
  public Response byOrder(@PathParam("orderId") UUID orderId) {
    var entries =
        svc.getPosLogByOrder(ctx.requireTenantId(), orderId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(entries)).build();
  }
}
