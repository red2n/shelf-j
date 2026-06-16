package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.CreateSpecialOrderRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Gap #42 — Special orders: customer orders placed at a store for future delivery. Distinct from
 * regular POS orders — no immediate inventory deduction, tracks customer contact and delivery date.
 */
@RequestScoped
@Path("/admin/special-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SpecialOrderResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreateSpecialOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var so = svc.createSpecialOrder(tenantId, req);
    var items = svc.getSpecialOrderItems(tenantId, so.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(so, items))).build();
  }

  @GET
  public Response list(
      @QueryParam("storeId") String storeId, @QueryParam("customerId") String customerId) {
    UUID tenantId = ctx.requireTenantId();
    var list =
        svc.listSpecialOrders(tenantId, storeId, customerId).stream()
            .map(so -> Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, so.id())))
            .toList();
    return Response.ok(ApiResponse.ok(list)).build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.getSpecialOrder(tenantId, id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.confirmSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.fulfilSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.cancelSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }
}
