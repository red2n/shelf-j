package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.RegisterSerialsRequest;
import com.shelfj.inventory.dto.Dtos.SerialMovementResponse;
import com.shelfj.inventory.dto.Dtos.SerialNumberResponse;
import com.shelfj.inventory.dto.Dtos.SerialStatusRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Serial number control: register, list, lookup, history, status. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SerialResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/serials/register")
  public Response registerSerials(RegisterSerialsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var items =
        service
            .registerSerials(
                tenantId,
                uuid(req.storeId(), "storeId"),
                uuid(req.variantId(), "variantId"),
                uuid(req.batchId(), "batchId"),
                req.serials(),
                req.autoQty(),
                req.prefix())
            .stream()
            .map(Mappers::toSerial)
            .toList();
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(items)).build();
  }

  @GET
  @Path("/serials")
  public ApiResponse<List<SerialNumberResponse>> listSerials(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String st = status == null || status.isBlank() ? null : status;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSerials(tenantId, storeId, variantId, st, limit).stream()
            .map(Mappers::toSerial)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/serials/lookup")
  public ApiResponse<SerialNumberResponse> lookupSerial(@QueryParam("serial_no") String serialNo) {
    if (serialNo == null || serialNo.isBlank())
      throw new ApiException(
          400, "SERIAL_NO_REQUIRED", "serial_no query param is required", List.of(), null);
    return ApiResponse.ok(
        Mappers.toSerial(service.lookupSerialByNo(ctx.requireTenantId(), serialNo)));
  }

  @GET
  @Path("/serials/{id}")
  public ApiResponse<SerialNumberResponse> getSerial(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toSerial(service.getSerial(ctx.requireTenantId(), id)));
  }

  @GET
  @Path("/serials/{id}/history")
  public ApiResponse<List<SerialMovementResponse>> serialHistory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    service.getSerial(tenantId, id); // 404 if not found
    var items =
        service.listSerialHistory(tenantId, id).stream().map(Mappers::toSerialMovement).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/serials/{id}/status")
  public ApiResponse<SerialNumberResponse> updateSerialStatus(
      @PathParam("id") UUID id, SerialStatusRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSerial(service.updateSerialStatus(tenantId, id, req.status())));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
