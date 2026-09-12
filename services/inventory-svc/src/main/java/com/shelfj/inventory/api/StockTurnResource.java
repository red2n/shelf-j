package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Domain.DeadStockGrouping;
import com.shelfj.inventory.domain.Domain.StockTurnGrouping;
import com.shelfj.inventory.dto.Dtos.DeadStockRowResponse;
import com.shelfj.inventory.dto.Dtos.StockTurnReportResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Stock turn and dead-stock ageing — the two inventory reports the readiness review still listed as
 * missing once shrinkage, valuation and low-stock had shipped.
 *
 * <p>They are one resource because they are one question asked from both ends: turn asks how fast
 * the holding moves, ageing asks what part of it has stopped. Both are answered from the same two
 * tables, and splitting them would have duplicated the cost-price handling that is the delicate
 * part of each.
 *
 * <p>Under {@code /admin/} so the authorisation filter gates them by path rather than by a role
 * check written into each method — the distinction that produced SJ-D10, and the reason SJ-D11 made
 * reads default-deny.
 *
 * <p><b>That sentence was not true when it was first written, and SJ-D19 is why it is now.</b>
 * {@code /admin/inventory/**} is the filter's <em>staff-operable</em> tier — a storekeeper has to
 * be able to receive stock — so these reports inherited it and answered a CASHIER with 200: stock
 * valuation, cost of goods sold, and by extension the shrinkage report naming which colleague wrote
 * off what. The prefix was right and the subtree was wrong, and only running it showed the
 * difference. {@code AdminAuthorizationFilter} now carves {@code /admin/inventory/reports} back out
 * to management, so this class and the three report resources beside it are gated by path after
 * all.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Stock Turn")
public class StockTurnResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 20;

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Hows many times the holding turned over.
   *
   * <p>Cost of goods sold in the window against the average value held to produce it, per store or
   * per variant. Cost comes from the batches the sales actually drew down, so a historical window
   * is answered with the costs of the day rather than today's. from and to are both required — a
   * turnover ratio has no meaning without a window, and daysOnHand divides by its length. Both
   * accept a full ISO-8601 instant (2026-01-31T00:00:00Z), not a bare date. Rows come back
   * slowest-turning first, which is the end of the list worth acting on.
   *
   * @param storeId the store id (query parameter)
   * @param from the from (query parameter)
   * @param to the to (query parameter)
   * @param groupBy the group by (query parameter)
   * @param limit the limit (query parameter)
   * @return rows plus whether opening values are complete
   * @throws com.shelfj.web.ApiException {@code 400} missing or unparseable from/to, from not before
   *     to, or unknown groupBy
   */
  @Operation(
      summary = "How many times the holding turned over",
      description =
          "Cost of goods sold in the window against the average value held to produce it, per store"
              + " or per variant. Cost comes from the batches the sales actually drew down, so a"
              + " historical window is answered with the costs of the day rather than today's."
              + " from and to are both required — a turnover ratio has no meaning without a window,"
              + " and daysOnHand divides by its length. Both accept a full ISO-8601 instant"
              + " (2026-01-31T00:00:00Z), not a bare date. Rows come back slowest-turning first,"
              + " which is the end of the list worth acting on.")
  @APIResponse(responseCode = "200", description = "Rows plus whether opening values are complete")
  @APIResponse(
      responseCode = "400",
      description = "Missing or unparseable from/to, from not before to, or unknown groupBy")
  @GET
  @Path("/reports/stock-turn")
  public Response stockTurn(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("groupBy") String groupBy,
      @QueryParam("limit") Integer limit) {
    // Required, so Parsing.instant rather than optionalInstant: an absent bound here is a
    // malformed request, not "no filter" (SJ-D9 — the caller gets the field name and the format).
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    StockTurnReportResponse report =
        Mappers.toStockTurnReport(
            service.stockTurnReport(
                ctx.requireTenantId(),
                Parsing.optionalUuid(storeId, "storeId"),
                Parsing.instant(from, "from"),
                Parsing.instant(to, "to"),
                turnGrouping(groupBy),
                clamped));
    return Response.ok(ApiResponse.ok(report, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /**
   * Stocks on hand, aged by time since it last sold.
   *
   * <p>The ageing ladder — 0-30, 31-60, 61-90, 91-180, 180+ days — with the value sitting in each
   * band. Age runs from the last sale of that item at that store, not from receipt: stock that
   * arrived two years ago and sold this morning is not dead. Stock that has never sold ages from
   * the arrival of its oldest remaining batch, and says so. Group by BUCKET for the ladder, or by
   * STORE or VARIANT to find what is in it.
   *
   * @param storeId the store id (query parameter)
   * @param asOf the as of (query parameter)
   * @param groupBy the group by (query parameter)
   * @param limit the limit (query parameter)
   * @return rows ordered by value at risk, largest first
   * @throws com.shelfj.web.ApiException {@code 400} unknown groupBy or unparseable asOf/storeId
   */
  @Operation(
      summary = "Stock on hand, aged by time since it last sold",
      description =
          "The ageing ladder — 0-30, 31-60, 61-90, 91-180, 180+ days — with the value sitting in"
              + " each band. Age runs from the last sale of that item at that store, not from"
              + " receipt: stock that arrived two years ago and sold this morning is not dead."
              + " Stock that has never sold ages from the arrival of its oldest remaining batch,"
              + " and says so. Group by BUCKET for the ladder, or by STORE or VARIANT to find what"
              + " is in it.")
  @APIResponse(responseCode = "200", description = "Rows ordered by value at risk, largest first")
  @APIResponse(responseCode = "400", description = "Unknown groupBy or unparseable asOf/storeId")
  @GET
  @Path("/reports/dead-stock")
  public Response deadStock(
      @QueryParam("storeId") String storeId,
      @QueryParam("asOf") String asOf,
      @QueryParam("groupBy") String groupBy,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    List<DeadStockRowResponse> rows =
        service
            .deadStockReport(
                ctx.requireTenantId(),
                Parsing.optionalUuid(storeId, "storeId"),
                Parsing.optionalInstant(asOf, "asOf"),
                deadStockGrouping(groupBy),
                clamped)
            .stream()
            .map(Mappers::toDeadStockRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /** Defaults to STORE — "how is each site doing?" is what this report gets opened with. */
  private static StockTurnGrouping turnGrouping(String raw) {
    if (raw == null || raw.isBlank()) return StockTurnGrouping.STORE;
    try {
      return StockTurnGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "INVENTORY_INVALID_GROUPING",
          "groupBy must be STORE or VARIANT — got: " + raw,
          List.of(),
          e);
    }
  }

  /** Defaults to BUCKET — the ladder is the report; the drill-downs are how you act on it. */
  private static DeadStockGrouping deadStockGrouping(String raw) {
    if (raw == null || raw.isBlank()) return DeadStockGrouping.BUCKET;
    try {
      return DeadStockGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "INVENTORY_INVALID_GROUPING",
          "groupBy must be BUCKET, STORE or VARIANT — got: " + raw,
          List.of(),
          e);
    }
  }
}
