package com.shelfj.reporting.api;

import com.shelfj.reporting.mapper.Mappers;
import com.shelfj.reporting.service.ReportingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/admin/reports/inventory")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject ReportingService service;
  @Inject TenantContext ctx;

  /** Gap #47: cross-store on-hand snapshot. */
  @GET
  @Path("/on-hand")
  public ApiResponse<Object> onHand(
      @QueryParam("storeId") String storeId, @QueryParam("variantId") String variantId) {
    var rows =
        service.onHand(
            ctx.tenantId(),
            storeId != null ? UUID.fromString(storeId) : null,
            variantId != null ? UUID.fromString(variantId) : null);
    return ApiResponse.ok(Mappers.toOnHandReport(rows));
  }

  /** Gap #48: supply/demand netting — on-hand + open in-transit supply lines. */
  @GET
  @Path("/supply-demand")
  public ApiResponse<Object> supplyDemand(
      @QueryParam("storeId") String storeId, @QueryParam("variantId") String variantId) {
    var result =
        service.supplyDemandNetting(
            ctx.tenantId(),
            storeId != null ? UUID.fromString(storeId) : null,
            variantId != null ? UUID.fromString(variantId) : null);
    return ApiResponse.ok(Mappers.toNettingReport(result));
  }

  /** Gap #49: movement statistics bucketed by day/week/month. */
  @GET
  @Path("/movement-stats")
  public ApiResponse<Object> movementStats(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("bucketDays") @DefaultValue("7") int bucketDays) {
    var stats =
        service.movementStats(
            ctx.tenantId(),
            storeId != null ? UUID.fromString(storeId) : null,
            variantId != null ? UUID.fromString(variantId) : null,
            bucketDays);
    return ApiResponse.ok(Mappers.toMovementStatsReport(stats));
  }
}
