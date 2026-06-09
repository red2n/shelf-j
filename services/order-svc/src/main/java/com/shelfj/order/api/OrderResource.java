package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.CreateReturnRequest;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.dto.Dtos.VoidRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
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

/** Order lifecycle: place, confirm, cancel, fulfil, void (POS), returns. */
@Path("/orders")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @POST
  public Response place(PlaceOrderRequest req) {
    Validations.validate(req);
    var order = svc.placeOrder(req, ctx);
    var items = svc.getOrderItems(order.tenantId(), order.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    var order = svc.getOrder(ctx.tenantId(), UUID.fromString(id));
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") String id) {
    var order = svc.confirmOrder(ctx.tenantId(), UUID.fromString(id), ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, VoidRequest req) {
    var order =
        svc.cancelOrder(
            ctx.tenantId(), UUID.fromString(id), req != null ? req.reason() : null, ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") String id) {
    var order = svc.fulfillOrder(ctx.tenantId(), UUID.fromString(id), ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @GET
  @Path("/{id}/history")
  public Response history(@PathParam("id") String id) {
    var hist = svc.getOrderHistory(ctx.tenantId(), UUID.fromString(id));
    return Response.ok(ApiResponse.ok(hist.stream().map(Mappers::toDto).toList())).build();
  }

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  @POST
  @Path("/{id}/void")
  public Response voidOrder(@PathParam("id") String id, VoidRequest req) {
    Validations.validate(req);
    var vl = svc.voidOrder(ctx.tenantId(), UUID.fromString(id), req, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(vl))).build();
  }

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  @POST
  @Path("/{id}/returns")
  public Response createReturn(@PathParam("id") String id, CreateReturnRequest req) {
    Validations.validate(req);
    var ret = svc.createReturn(ctx.tenantId(), UUID.fromString(id), req, ctx);
    var retItems = svc.getReturnItems(ctx.tenantId(), ret.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(ret, retItems))).build();
  }

  @GET
  @Path("/{id}/returns")
  public Response listReturns(@PathParam("id") String id) {
    var returns = svc.getReturns(ctx.tenantId(), UUID.fromString(id));
    var dtos =
        returns.stream()
            .map(
                r -> {
                  var ri = svc.getReturnItems(ctx.tenantId(), r.id());
                  return Mappers.toDto(r, ri);
                })
            .toList();
    return Response.ok(ApiResponse.ok(dtos)).build();
  }
}
