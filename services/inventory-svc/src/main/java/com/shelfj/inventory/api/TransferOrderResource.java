package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.inventory.dto.Dtos.CreateTransferOrderRequest;
import com.shelfj.inventory.dto.Dtos.TransferOrderResponse;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Transfer orders (Gap #6): inter-store stock transfers. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transfer Orders")
public class TransferOrderResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a transfer order",
      description = "Requests one or more variants be transferred between two stores.")
  @APIResponse(responseCode = "201", description = "Transfer order created")
  @POST
  @Path("/transfers")
  public Response createTransfer(CreateTransferOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    List<TransferOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new TransferOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null,
                        null))
            .toList();
    var wl =
        service.createTransferOrder(
            tenantId, fromStore, toStore, req.transferType(), req.notes(), lines);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines())))
        .build();
  }

  @Operation(summary = "List transfer orders", description = "Filterable by store and status.")
  @GET
  @Path("/transfers")
  public ApiResponse<List<TransferOrderResponse>> listTransfers(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listTransferOrders(tenantId, storeId, status, limit).stream()
            .map(
                o -> Mappers.toTransferOrder(o, service.getTransferOrder(tenantId, o.id()).lines()))
            .toList());
  }

  @Operation(summary = "Get a transfer order by id, with its lines")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @GET
  @Path("/transfers/{id}")
  public ApiResponse<TransferOrderResponse> getTransfer(@PathParam("id") UUID id) {
    var wl = service.getTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @Operation(
      summary = "Ship a transfer order",
      description = "Marks the order shipped, deducting the shipped qty from the source store.")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @APIResponse(responseCode = "422", description = "Transfer order is not in a shippable state")
  @POST
  @Path("/transfers/{id}/ship")
  public ApiResponse<TransferOrderResponse> shipTransfer(@PathParam("id") UUID id) {
    var wl = service.shipTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @Operation(
      summary = "Receive a transfer order",
      description = "Marks the order received, adding the shipped qty into the destination store.")
  @APIResponse(responseCode = "404", description = "No such transfer order")
  @APIResponse(responseCode = "422", description = "Transfer order is not in a receivable state")
  @POST
  @Path("/transfers/{id}/receive")
  public ApiResponse<TransferOrderResponse> receiveTransfer(@PathParam("id") UUID id) {
    var wl = service.receiveTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @Operation(
      summary = "Cancel a transfer order",
      description = "Only PENDING transfer orders can be cancelled.")
  @APIResponse(responseCode = "422", description = "Only PENDING transfer orders can be cancelled")
  @POST
  @Path("/transfers/{id}/cancel")
  public ApiResponse<TransferOrderResponse> cancelTransfer(@PathParam("id") UUID id) {
    var cancelled = service.cancelTransferOrder(ctx.requireTenantId(), id);
    var wl = service.getTransferOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
