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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/admin/reports/inventory")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Inventory Reports")
public class AdminResource {

  @Inject ReportingService service;
  @Inject TenantContext ctx;

  /** Gap #47: cross-store on-hand snapshot. */
  @Operation(
      summary = "Cross-store on-hand snapshot",
      description =
          "On-hand quantity per store/variant, projected from consumed stock-movement events."
              + " Optionally filtered by store and/or variant.")
  @APIResponse(responseCode = "200", description = "On-hand rows plus a grand total")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
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
  @Operation(
      summary = "Supply/demand netting report",
      description =
          "Nets on-hand quantity against open in-transit supply lines per store/variant, to show"
              + " net available. Optionally filtered by store and/or variant.")
  @APIResponse(responseCode = "200", description = "Netting rows")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
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
  @Operation(
      summary = "Movement statistics report",
      description =
          "Stock in/out/net movement totals per store/variant, bucketed by the given number of"
              + " days (e.g. 1 for daily, 7 for weekly, 30 for monthly-ish buckets).")
  @APIResponse(responseCode = "200", description = "Movement statistic rows")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
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
