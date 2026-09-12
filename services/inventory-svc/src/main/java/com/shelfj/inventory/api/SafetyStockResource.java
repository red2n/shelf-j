package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.ComputeSafetyStockRequest;
import com.shelfj.inventory.dto.Dtos.ComputeSafetyStockResult;
import com.shelfj.inventory.dto.Dtos.SafetyStockParamsResponse;
import com.shelfj.inventory.dto.Dtos.SetSafetyStockRequest;
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

/** Safety stock parameters (Gap #8). Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Safety Stock")
public class SafetyStockResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Sets safety-stock parameters for a variant at a store.
   *
   * <p>method must be MAD or USER_DEFINED; USER_DEFINED requires a positive userDefinedPct.
   *
   * @param req the request body
   * @return safety-stock parameters set ({@code 201})
   * @throws com.shelfj.web.ApiException {@code 400} invalid method, or userDefinedPct missing when
   *     method is USER_DEFINED
   */
  @Operation(
      summary = "Set safety-stock parameters for a variant at a store",
      description =
          "method must be MAD or USER_DEFINED; USER_DEFINED requires a positive"
              + " userDefinedPct.")
  @APIResponse(responseCode = "201", description = "Safety-stock parameters set")
  @APIResponse(
      responseCode = "400",
      description = "Invalid method, or userDefinedPct missing when method is USER_DEFINED")
  @POST
  @Path("/safety-stock")
  public Response setSafetyStock(SetSafetyStockRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var params =
        service.setSafetyStockParams(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.method(),
            req.leadTimeDays(),
            req.serviceLevelPct(),
            req.userDefinedPct());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toSafetyStockParams(params)))
        .build();
  }

  /**
   * Lists safety-stock parameters for a store.
   *
   * @param store the store (query parameter)
   * @param limitParam the limit param (query parameter)
   */
  @Operation(summary = "List safety-stock parameters for a store")
  @APIResponse(responseCode = "200", description = "List safety-stock parameters for a store")
  @GET
  @Path("/safety-stock")
  public ApiResponse<List<SafetyStockParamsResponse>> listSafetyStock(
      @QueryParam("store") String store, @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSafetyStockParams(tenantId, storeId, limit).stream()
            .map(Mappers::toSafetyStockParams)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Gets safety-stock parameters for a specific variant at a store.
   *
   * @param storeId the store id (path parameter)
   * @param variantId the variant id (path parameter)
   */
  @Operation(summary = "Get safety-stock parameters for a specific variant at a store")
  @APIResponse(
      responseCode = "200",
      description = "Get safety-stock parameters for a specific variant at a store")
  @GET
  @Path("/safety-stock/{storeId}/{variantId}")
  public ApiResponse<SafetyStockParamsResponse> getSafetyStock(
      @PathParam("storeId") UUID storeId, @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSafetyStockParams(service.getSafetyStockParams(tenantId, storeId, variantId)));
  }

  /**
   * Recomputes safety-stock quantities.
   *
   * <p>Recalculates safetyStockQty from demand-history buckets for the given store/variant scope
   * (or all, if omitted).
   *
   * @param req the request body
   */
  @Operation(
      summary = "Recompute safety-stock quantities",
      description =
          "Recalculates safetyStockQty from demand-history buckets for the given"
              + " store/variant scope (or all, if omitted).")
  @APIResponse(responseCode = "200", description = "Recompute safety-stock quantities")
  @POST
  @Path("/safety-stock/compute")
  public ApiResponse<ComputeSafetyStockResult> computeSafetyStock(ComputeSafetyStockRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    UUID variantId =
        req != null && req.variantId() != null && !req.variantId().isBlank()
            ? uuid(req.variantId(), "variantId")
            : null;
    int updated = service.computeSafetyStock(tenantId, storeId, variantId);
    return ApiResponse.ok(new ComputeSafetyStockResult(updated, "DAY"));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
