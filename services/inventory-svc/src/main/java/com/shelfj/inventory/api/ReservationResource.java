package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.ReserveRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Internal reservation API (called by order-svc during checkout): hold stock, then consume on
 * confirm or release on cancel/timeout. Tenant-scoped via context (gateway forwards identity).
 */
@Path("/inventory/reservations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReservationResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  public Response reserve(ReserveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID orderId =
        req.orderId() == null || req.orderId().isBlank() ? null : uuid(req.orderId(), "orderId");
    var r =
        service.reserve(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.qty(),
            orderId,
            req.ttlSeconds());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toReservation(r)))
        .build();
  }

  @POST
  @Path("/{id}/consume")
  public ApiResponse<String> consume(@PathParam("id") UUID id) {
    service.consume(ctx.requireTenantId(), id);
    return ApiResponse.ok("consumed");
  }

  @POST
  @Path("/{id}/release")
  public ApiResponse<String> release(@PathParam("id") UUID id) {
    boolean released = service.release(ctx.requireTenantId(), id);
    return ApiResponse.ok(released ? "released" : "noop");
  }

  private static UUID uuid(String s, String field) {
    try {
      return UUID.fromString(s);
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }
}
