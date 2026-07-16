package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.LotUomConversionResponse;
import com.shelfj.inventory.dto.Dtos.ParLevelResponse;
import com.shelfj.inventory.dto.Dtos.UpsertLotUomConversionRequest;
import com.shelfj.inventory.dto.Dtos.UpsertParLevelRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

/**
 * Lot UOM conversions (Gap #26) and PAR levels (Gap #27) — matches {@code
 * PlanningConfigRepository}'s grouping at the data layer. Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningConfigResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @PUT
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<LotUomConversionResponse> upsertLotUomConversion(
      @PathParam("batchId") UUID batchId, UpsertLotUomConversionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var c =
        service.upsertLotUomConversion(
            tenantId, batchId, req.fromUom(), req.toUom(), req.factor(), req.notes());
    return ApiResponse.ok(Mappers.toLotUomConversion(c));
  }

  @GET
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<List<LotUomConversionResponse>> listLotUomConversions(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotUomConversions(tenantId, batchId).stream()
            .map(Mappers::toLotUomConversion)
            .toList());
  }

  @PUT
  @Path("/par-levels")
  public ApiResponse<ParLevelResponse> upsertParLevel(UpsertParLevelRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var p =
        service.upsertParLevel(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.parQty(),
            req.uom(),
            req.reviewCycle());
    return ApiResponse.ok(Mappers.toParLevel(p));
  }

  @GET
  @Path("/par-levels")
  public ApiResponse<List<ParLevelResponse>> listParLevels(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listParLevels(tenantId, storeId).stream().map(Mappers::toParLevel).toList());
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
