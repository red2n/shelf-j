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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reorder thresholds and min-max replenishment planning. Extracted from {@code AdminResource} (Gap
 * #2 in the F2 audit finding: split by sub-domain path group).
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reorder Thresholds & Planning")
public class PlanningResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Sets a reorder threshold for a variant at a store.
   *
   * <p>min threshold that triggers a low-stock suggestion, plus an optional max qty.
   *
   * @param req the request body
   */
  @Operation(
      summary = "Set a reorder threshold for a variant at a store",
      description = "min threshold that triggers a low-stock suggestion, plus an optional max qty.")
  @APIResponse(responseCode = "201", description = "Created")
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

  /**
   * Lists reorder thresholds.
   *
   * <p>Filterable by store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List reorder thresholds", description = "Filterable by store.")
  @APIResponse(responseCode = "200", description = "List reorder thresholds")
  @GET
  @Path("/thresholds")
  public ApiResponse<List<ThresholdResponse>> listThresholds(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.listThresholds(tenantId, storeId).stream().map(Mappers::toThreshold).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Runs the min-max replenishment plan.
   *
   * <p>Generates replenishment suggestions for variants whose available qty has fallen below its
   * threshold.
   *
   * @param store the store (query parameter)
   */
  @Operation(
      summary = "Run the min-max replenishment plan",
      description =
          "Generates replenishment suggestions for variants whose available qty has"
              + " fallen below its threshold.")
  @APIResponse(responseCode = "200", description = "Run the min-max replenishment plan")
  @POST
  @Path("/planning/run")
  public ApiResponse<List<SuggestionResponse>> runMinMaxPlan(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.runMinMaxPlan(tenantId, storeId).stream().map(Mappers::toSuggestion).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Lists replenishment suggestions.
   *
   * <p>Filterable by store and status.
   *
   * @param store the store (query parameter)
   * @param status the status (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(
      summary = "List replenishment suggestions",
      description = "Filterable by store and status.")
  @APIResponse(responseCode = "200", description = "List replenishment suggestions")
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

  /**
   * Resolves a replenishment suggestion.
   *
   * <p>Sets the suggestion's status (e.g. ACCEPTED/DISMISSED).
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.shelfj.web.ApiException {@code 404} no open suggestion with that id
   */
  @Operation(
      summary = "Resolve a replenishment suggestion",
      description = "Sets the suggestion's status (e.g. ACCEPTED/DISMISSED).")
  @APIResponse(responseCode = "404", description = "No open suggestion with that id")
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
