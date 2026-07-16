package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.BatchReserveRequest;
import com.shelfj.inventory.dto.Dtos.BatchReserveResponse;
import com.shelfj.inventory.dto.Dtos.ReservationResponse;
import com.shelfj.inventory.dto.Dtos.ReserveRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reservation API (called by order-svc during checkout): hold, consume, release, and read.
 * Tenant-scoped via context (gateway forwards identity).
 */
@Path("/inventory/reservations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reservations")
public class ReservationResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Hold stock for an order",
      description =
          "Places a time-bounded HELD reservation against available stock (FIFO/expiry-ordered)."
              + " Supports Idempotency-Key so a retried checkout does not double-reserve.")
  @APIResponse(responseCode = "201", description = "Reservation held")
  @APIResponse(responseCode = "422", description = "Not enough stock to reserve")
  @POST
  public Response reserve(
      @HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ReserveRequest req) {
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
            req.ttlSeconds(),
            idempotencyKey);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toReservation(r)))
        .build();
  }

  @Operation(summary = "List reservations", description = "Filterable by store and status.")
  @GET
  public ApiResponse<List<ReservationResponse>> listReservations(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listReservations(tenantId, storeId, status, limit).stream()
            .map(Mappers::toReservation)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Get a reservation by id")
  @APIResponse(responseCode = "404", description = "No such reservation")
  @GET
  @Path("/{id}")
  public ApiResponse<ReservationResponse> getReservation(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toReservation(service.getReservation(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Consume a held reservation",
      description =
          "FIFO/expiry-ordered deduction of the reserved qty from the underlying batches"
              + " once the order is confirmed.")
  @APIResponse(responseCode = "422", description = "Reservation is not in a HELD state")
  @POST
  @Path("/{id}/consume")
  public ApiResponse<String> consume(@PathParam("id") UUID id) {
    service.consume(ctx.requireTenantId(), id);
    return ApiResponse.ok("consumed");
  }

  @Operation(
      summary = "Release a held reservation",
      description = "Returns the held qty to available stock; a no-op if already released/expired.")
  @POST
  @Path("/{id}/release")
  public ApiResponse<String> release(@PathParam("id") UUID id) {
    boolean released = service.release(ctx.requireTenantId(), id);
    return ApiResponse.ok(released ? "released" : "noop");
  }

  // ── Gap #29: Bulk (batch) reservations ────────────────────────────────────

  @Operation(
      summary = "Reserve stock for multiple lines in one call",
      description =
          "Best-effort bulk reserve: each line is attempted independently and the"
              + " succeeded/failed counts plus per-line results are returned.")
  @POST
  @Path("/batch")
  public ApiResponse<BatchReserveResponse> bulkReserve(BatchReserveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result = service.bulkReserve(tenantId, req.reservations());
    var responses = result.results().stream().map(Mappers::toReservation).toList();
    return ApiResponse.ok(new BatchReserveResponse(result.succeeded(), result.failed(), responses));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
