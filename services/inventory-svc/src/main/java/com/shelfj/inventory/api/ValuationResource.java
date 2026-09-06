package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Domain.ValuationGrouping;
import com.shelfj.inventory.dto.Dtos.ValuationRowResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The stock valuation report — what the inventory on hand is worth.
 *
 * <p>Named in {@code docs/reporting-api-gap-analysis.md} as designed but unbuilt. It is the second
 * of the four reports listed there; the shrinkage report was the first.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Valuation")
public class ValuationResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Value the stock on hand",
      description =
          "Values remaining batch quantities on the costing basis configured per store and variant:"
              + " FIFO values each batch at its own cost, AVERAGE values the holding at the"
              + " configured standard cost. Group by STORE for a rollup or VARIANT for the detail."
              + " Quantities that carry no cost are reported as unvaluedQty rather than valued at"
              + " zero, so the figure is never silently understated.")
  @APIResponse(responseCode = "200", description = "Rows ordered by value, largest holding first")
  @APIResponse(responseCode = "400", description = "Unknown groupBy or malformed storeId")
  @GET
  @Path("/reports/valuation")
  public Response valuation(
      @QueryParam("storeId") String storeId,
      @QueryParam("groupBy") String groupBy,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    List<ValuationRowResponse> rows =
        service
            .valuationReport(ctx.requireTenantId(), storeUuid(storeId), grouping(groupBy), clamped)
            .stream()
            .map(Mappers::toValuationRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /** Defaults to STORE: "what is our stock worth?" is asked at site level before line level. */
  private static ValuationGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return ValuationGrouping.STORE;
    try {
      return ValuationGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(
          "INVENTORY_INVALID_GROUPING", "groupBy must be STORE or VARIANT — got: " + raw);
    }
  }

  private static UUID storeUuid(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("INVENTORY_INVALID_UUID", "storeId is not a valid UUID");
    }
  }
}
