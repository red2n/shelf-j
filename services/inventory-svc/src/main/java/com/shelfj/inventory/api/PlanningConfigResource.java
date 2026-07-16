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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Lot UOM conversions (Gap #26) and PAR levels (Gap #27) — matches {@code
 * PlanningConfigRepository}'s grouping at the data layer. Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Planning Configuration")
public class PlanningConfigResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Upsert a lot's unit-of-measure conversion",
      description = "Defines the factor to convert between two UOMs for a specific batch.")
  @APIResponse(responseCode = "400", description = "UOM conversion factor must be positive")
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

  @Operation(summary = "List a batch's UOM conversions")
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

  @Operation(
      summary = "Upsert a PAR level",
      description =
          "Sets the target periodic-automatic-replenishment quantity for a variant at a"
              + " store.")
  @APIResponse(responseCode = "400", description = "parQty must be positive")
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

  @Operation(summary = "List PAR levels for a store")
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
