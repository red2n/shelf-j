package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.ComputeRopResult;
import com.shelfj.inventory.dto.Dtos.RopPlanResponse;
import com.shelfj.inventory.dto.Dtos.UpdateOrderModifiersRequest;
import com.shelfj.inventory.dto.Dtos.UpsertRopPlanRequest;
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
import java.util.List;
import java.util.UUID;

/**
 * Reorder point / EOQ plans (Gap #19), including their order-modifier fields (Gap #28's ROP half —
 * matches {@code ReorderPointRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReorderPointResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @PUT
  @Path("/rop-plans")
  public ApiResponse<RopPlanResponse> upsertRopPlan(UpsertRopPlanRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.upsertRopPlan(
                tenantId,
                storeId,
                variantId,
                req.leadTimeDays(),
                req.orderingCost(),
                req.holdingCostPct(),
                req.unitCost())));
  }

  @GET
  @Path("/rop-plans")
  public ApiResponse<List<RopPlanResponse>> listRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listRopPlans(tenantId, storeId).stream().map(Mappers::toRopPlan).toList());
  }

  @GET
  @Path("/rop-plans/by-variant")
  public ApiResponse<RopPlanResponse> getRopPlan(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(Mappers.toRopPlan(service.getRopPlan(tenantId, storeId, variantId)));
  }

  @POST
  @Path("/rop-plans/compute")
  public ApiResponse<ComputeRopResult> computeRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int count = service.computeRopPlans(tenantId, storeId);
    return ApiResponse.ok(new ComputeRopResult(count));
  }

  @PUT
  @Path("/rop-plans/{id}/order-modifiers")
  public ApiResponse<RopPlanResponse> updateRopModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.updateRopOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
