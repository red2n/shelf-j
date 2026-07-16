package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.dto.Dtos.CreateMoveOrderRequest;
import com.shelfj.inventory.dto.Dtos.MoveOrderResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Move orders (Gap #5): intra-store zone-to-zone stock moves. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MoveOrderResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/move-orders")
  public Response createMoveOrder(CreateMoveOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    List<MoveOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new MoveOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null))
            .toList();
    var order =
        service.createMoveOrder(
            tenantId, fromStore, toStore, req.fromZone(), req.toZone(), req.notes(), lines);
    var withLines = service.getMoveOrder(tenantId, order.id());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toMoveOrder(withLines.order(), withLines.lines())))
        .build();
  }

  @GET
  @Path("/move-orders")
  public ApiResponse<List<MoveOrderResponse>> listMoveOrders(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listMoveOrders(tenantId, storeId, status, limit).stream()
            .map(o -> Mappers.toMoveOrder(o, service.getMoveOrder(tenantId, o.id()).lines()))
            .toList());
  }

  @GET
  @Path("/move-orders/{id}")
  public ApiResponse<MoveOrderResponse> getMoveOrder(@PathParam("id") UUID id) {
    var wl = service.getMoveOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/move-orders/{id}/pick")
  public ApiResponse<MoveOrderResponse> pickMoveOrder(@PathParam("id") UUID id) {
    var wl = service.pickMoveOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/move-orders/{id}/cancel")
  public ApiResponse<MoveOrderResponse> cancelMoveOrder(@PathParam("id") UUID id) {
    var cancelled = service.cancelMoveOrder(ctx.requireTenantId(), id);
    var wl = service.getMoveOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
