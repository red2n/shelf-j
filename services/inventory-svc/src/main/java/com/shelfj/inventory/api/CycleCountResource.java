package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.CreateCycleCountRequest;
import com.shelfj.inventory.dto.Dtos.CycleCountAdjustResult;
import com.shelfj.inventory.dto.Dtos.CycleCountApproveResult;
import com.shelfj.inventory.dto.Dtos.CycleCountHeaderResponse;
import com.shelfj.inventory.dto.Dtos.CycleCountLineResponse;
import com.shelfj.inventory.dto.Dtos.EnterCountRequest;
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

/** Cycle counting (Gap #10): headers, lines, approve/adjust. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CycleCountResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/cycle-counts")
  public Response createCycleCount(CreateCycleCountRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.createCycleCount(
            tenantId,
            uuid(req.storeId(), "storeId"),
            req.name(),
            req.abcClasses() != null ? req.abcClasses() : "A,B,C",
            req.tolerancePct() != null ? req.tolerancePct() : java.math.BigDecimal.valueOf(5));
    var resp = Mappers.toCycleCountHeader(result.header(), result.lines());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(resp, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/cycle-counts")
  public ApiResponse<List<CycleCountHeaderResponse>> listCycleCounts(
      @QueryParam("storeId") UUID storeId,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    var headers = service.listCycleCounts(tenantId, storeId, status, lim);
    var items =
        headers.stream().map(cwl -> Mappers.toCycleCountHeader(cwl.header(), cwl.lines())).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/cycle-counts/{id}")
  public ApiResponse<CycleCountHeaderResponse> getCycleCount(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var cwl = service.getCycleCount(tenantId, id);
    return ApiResponse.ok(
        Mappers.toCycleCountHeader(cwl.header(), cwl.lines()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/lines/{lineId}/count")
  public ApiResponse<CycleCountLineResponse> enterCount(
      @PathParam("id") UUID headerId, @PathParam("lineId") UUID lineId, EnterCountRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var line = service.enterCount(tenantId, headerId, lineId, req.countedQty());
    return ApiResponse.ok(Mappers.toCycleCountLine(line), ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/approve")
  public ApiResponse<CycleCountApproveResult> approveCycleCount(@PathParam("id") UUID headerId) {
    UUID tenantId = ctx.requireTenantId();
    var result = service.approveWithTolerance(tenantId, headerId);
    return ApiResponse.ok(
        new CycleCountApproveResult(result.autoApproved(), result.flagged()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/adjust")
  public ApiResponse<CycleCountAdjustResult> adjustCycleCount(@PathParam("id") UUID headerId) {
    UUID tenantId = ctx.requireTenantId();
    int adjusted = service.adjustCycleCount(tenantId, headerId);
    return ApiResponse.ok(
        new CycleCountAdjustResult(adjusted), ApiResponse.Meta.of(ctx.requestId()));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
