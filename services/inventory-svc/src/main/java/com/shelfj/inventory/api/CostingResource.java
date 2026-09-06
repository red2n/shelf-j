package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AccountingPeriodResponse;
import com.shelfj.inventory.dto.Dtos.CostingMethodResponse;
import com.shelfj.inventory.dto.Dtos.OpenPeriodRequest;
import com.shelfj.inventory.dto.Dtos.UpsertCostingMethodRequest;
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

/**
 * Costing methods (Gap #17) and accounting periods — bundled together as they were in
 * AdminResource's original section (matches {@code CostingRepository}'s grouping at the data
 * layer). Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Costing & Accounting Periods")
public class CostingResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Set the costing method for a variant at a store",
      description = "method must be FIFO or AVERAGE.")
  @APIResponse(responseCode = "400", description = "method is not FIFO or AVERAGE")
  @PUT
  @Path("/costing-methods")
  public ApiResponse<CostingMethodResponse> upsertCostingMethod(UpsertCostingMethodRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toCostingMethod(
            service.upsertCostingMethod(
                tenantId, storeId, variantId, req.method(), req.averageCost())));
  }

  @Operation(summary = "List costing methods for a store")
  @GET
  @Path("/costing-methods")
  public ApiResponse<List<CostingMethodResponse>> listCostingMethods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listCostingMethods(tenantId, storeId).stream()
            .map(Mappers::toCostingMethod)
            .toList());
  }

  @Operation(summary = "Get the costing method for a specific variant at a store")
  @APIResponse(responseCode = "404", description = "costing method not found")
  @GET
  @Path("/costing-methods/by-variant")
  public ApiResponse<CostingMethodResponse> getCostingMethod(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(
        Mappers.toCostingMethod(service.getCostingMethod(tenantId, storeId, variantId)));
  }

  @Operation(summary = "Open a new accounting period for a store")
  @APIResponse(responseCode = "201", description = "Period opened")
  @POST
  @Path("/accounting-periods")
  public Response openPeriod(OpenPeriodRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    var period = service.openPeriod(tenantId, storeId, req.periodName(), req.periodDate());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toPeriod(period)))
        .build();
  }

  @Operation(summary = "List accounting periods for a store")
  @GET
  @Path("/accounting-periods")
  public ApiResponse<List<AccountingPeriodResponse>> listPeriods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listPeriods(tenantId, storeId).stream().map(Mappers::toPeriod).toList());
  }

  @Operation(summary = "Get an accounting period by id")
  @APIResponse(responseCode = "404", description = "accounting period not found")
  @GET
  @Path("/accounting-periods/{id}")
  public ApiResponse<AccountingPeriodResponse> getPeriod(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toPeriod(service.getPeriod(tenantId, id)));
  }

  @Operation(
      summary = "Close an accounting period",
      description = "Locks the period so no further costed movements can post into it.")
  @APIResponse(responseCode = "404", description = "accounting period not found")
  @POST
  @Path("/accounting-periods/{id}/close")
  public ApiResponse<AccountingPeriodResponse> closePeriod(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toPeriod(service.closePeriod(tenantId, id)));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
