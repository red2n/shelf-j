package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.ResolveSuggestionRequest;
import com.shelfj.inventory.dto.Dtos.SuggestionResponse;
import com.shelfj.inventory.dto.Dtos.ThresholdRequest;
import com.shelfj.inventory.dto.Dtos.ThresholdResponse;
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

/**
 * Reorder thresholds and min-max replenishment planning. Extracted from {@code AdminResource} (Gap
 * #2 in the F2 audit finding: split by sub-domain path group).
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/thresholds")
  public Response setThreshold(ThresholdRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var t =
        service.setThreshold(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.threshold(),
            req.maxQty());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toThreshold(t)))
        .build();
  }

  @GET
  @Path("/thresholds")
  public ApiResponse<List<ThresholdResponse>> listThresholds(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.listThresholds(tenantId, storeId).stream().map(Mappers::toThreshold).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/planning/run")
  public ApiResponse<List<SuggestionResponse>> runMinMaxPlan(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.runMinMaxPlan(tenantId, storeId).stream().map(Mappers::toSuggestion).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/planning/suggestions")
  public ApiResponse<List<SuggestionResponse>> listSuggestions(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    String st = status == null || status.isBlank() ? null : status;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSuggestions(tenantId, storeId, st, limit).stream()
            .map(Mappers::toSuggestion)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/planning/suggestions/{id}/status")
  public ApiResponse<SuggestionResponse> resolveSuggestion(
      @PathParam("id") UUID id, ResolveSuggestionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSuggestion(service.resolveSuggestion(tenantId, id, req.status())));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
