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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Serial number control: register, list, lookup, history, status. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Serial Numbers")
public class SerialResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Registers serial numbers for a batch.
   *
   * <p>Registers explicit serials or auto-generates up to 200 with an optional prefix.
   *
   * @param req the request body
   * @return serials registered ({@code 201})
   * @throws com.shelfj.web.ApiException {@code 400} autoQty exceeds 200
   */
  @Operation(
      summary = "Register serial numbers for a batch",
      description =
          "Registers explicit serials or auto-generates up to 200 with an optional" + " prefix.")
  @APIResponse(responseCode = "201", description = "Serials registered")
  @APIResponse(responseCode = "400", description = "autoQty exceeds 200")
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

  /**
   * Lists serial numbers.
   *
   * <p>Filterable by store, variant, and status.
   *
   * @param store the store (query parameter)
   * @param variant the variant (query parameter)
   * @param status the status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(
      summary = "List serial numbers",
      description = "Filterable by store, variant, and status.")
  @APIResponse(responseCode = "200", description = "List serial numbers")
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

  /**
   * Looks up a serial number by its serial code.
   *
   * @param serialNo the serial no (query parameter)
   * @throws com.shelfj.web.ApiException {@code 400} serial_no query param is required; {@code 404}
   *     no such serial number
   */
  @Operation(summary = "Look up a serial number by its serial code")
  @APIResponse(responseCode = "400", description = "serial_no query param is required")
  @APIResponse(responseCode = "404", description = "No such serial number")
  @GET
  @Path("/serials/lookup")
  public ApiResponse<SerialNumberResponse> lookupSerial(@QueryParam("serial_no") String serialNo) {
    if (serialNo == null || serialNo.isBlank())
      throw new ApiException(
          400, "SERIAL_NO_REQUIRED", "serial_no query param is required", List.of(), null);
    return ApiResponse.ok(
        Mappers.toSerial(service.lookupSerialByNo(ctx.requireTenantId(), serialNo)));
  }

  /**
   * Gets a serial number by id.
   *
   * @param id the id (path parameter)
   * @throws com.shelfj.web.ApiException {@code 404} no such serial number
   */
  @Operation(summary = "Get a serial number by id")
  @APIResponse(responseCode = "404", description = "No such serial number")
  @GET
  @Path("/serials/{id}")
  public ApiResponse<SerialNumberResponse> getSerial(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toSerial(service.getSerial(ctx.requireTenantId(), id)));
  }

  /**
   * Gets a serial number's status history.
   *
   * @param id the id (path parameter)
   * @throws com.shelfj.web.ApiException {@code 404} no such serial number
   */
  @Operation(summary = "Get a serial number's status history")
  @APIResponse(responseCode = "404", description = "No such serial number")
  @GET
  @Path("/serials/{id}/history")
  public ApiResponse<List<SerialMovementResponse>> serialHistory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    service.getSerial(tenantId, id); // 404 if not found
    var items =
        service.listSerialHistory(tenantId, id).stream().map(Mappers::toSerialMovement).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Updates a serial number's status.
   *
   * <p>Records a status transition (e.g. IN_STOCK -> SOLD) in the serial's movement history.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.shelfj.web.ApiException {@code 404} no such serial number
   */
  @Operation(
      summary = "Update a serial number's status",
      description =
          "Records a status transition (e.g. IN_STOCK -> SOLD) in the serial's movement"
              + " history.")
  @APIResponse(responseCode = "404", description = "No such serial number")
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
