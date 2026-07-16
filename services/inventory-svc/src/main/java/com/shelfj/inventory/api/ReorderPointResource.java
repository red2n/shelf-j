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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reorder point / EOQ plans (Gap #19), including their order-modifier fields (Gap #28's ROP half —
 * matches {@code ReorderPointRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reorder Point & EOQ")
public class ReorderPointResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Upsert a reorder-point/EOQ plan",
      description =
          "Sets the lead time, ordering cost, holding cost %, and unit cost inputs used"
              + " to compute the variant's ROP and economic order quantity.")
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

  @Operation(summary = "List ROP/EOQ plans for a store")
  @GET
  @Path("/rop-plans")
  public ApiResponse<List<RopPlanResponse>> listRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listRopPlans(tenantId, storeId).stream().map(Mappers::toRopPlan).toList());
  }

  @Operation(summary = "Get the ROP/EOQ plan for a specific variant at a store")
  @APIResponse(responseCode = "404", description = "ROP plan not found")
  @GET
  @Path("/rop-plans/by-variant")
  public ApiResponse<RopPlanResponse> getRopPlan(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(Mappers.toRopPlan(service.getRopPlan(tenantId, storeId, variantId)));
  }

  @Operation(
      summary = "Recompute ROP and EOQ for a store's plans",
      description = "Recalculates avgDailyDemand-derived rop and eoq for every plan at the store.")
  @POST
  @Path("/rop-plans/compute")
  public ApiResponse<ComputeRopResult> computeRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int count = service.computeRopPlans(tenantId, storeId);
    return ApiResponse.ok(new ComputeRopResult(count));
  }

  @Operation(
      summary = "Update a ROP plan's order modifiers",
      description =
          "Sets min/max order quantity and lot-size multiplier applied to the computed"
              + " EOQ (Gap #28).")
  @APIResponse(responseCode = "404", description = "ROP plan not found")
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
