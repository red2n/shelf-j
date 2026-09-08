package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.SalesByHourRowResponse;
import com.shelfj.order.dto.Dtos.SalesByStaffRowResponse;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Sales by hour of day and by member of staff — two of the four reports the readiness review still
 * listed as missing.
 *
 * <p>Both live in order-svc because the data does: reporting-svc's {@code sales_facts} carries one
 * row per order with no cashier on it, and the POS transaction journal that knows who served whom
 * is this service's table.
 *
 * <p>Under {@code /admin/} so the authorisation filter gates them by path — takings per cashier is
 * management information, and the by-path form is what stops a method added here later from
 * shipping open (SJ-D10, SJ-D11).
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reports")
public class SalesAnalyticsResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 50;

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Takings by hour of the trading day",
      description =
          "Revenue orders bucketed by hour, so a manager can see when the shop is actually busy and"
              + " staff it accordingly. Pass tz as an IANA zone name (Europe/London, Asia/Kolkata)"
              + " to count hours on that clock — everything is stored in UTC, and without tz a"
              + " London peak lands an hour out in summer and an Indian one half an hour out all"
              + " year. Filter by channel to compare the till against the website. Only CONFIRMED"
              + " and FULFILLED orders count; hours with no trade are absent rather than zero."
              + " from and to take a full ISO-8601 instant, not a bare date.")
  @APIResponse(responseCode = "200", description = "One row per hour that traded, earliest first")
  @APIResponse(
      responseCode = "400",
      description = "Unknown tz or channel, unparseable timestamp, or from is not before to")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER or MANAGER")
  @GET
  @Path("/sales-by-hour")
  public Response salesByHour(
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("tz") String tz) {
    List<SalesByHourRowResponse> rows =
        svc
            .salesByHour(
                ctx.requireTenantId(),
                Parsing.optionalUuid(storeId, "storeId"),
                channel,
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                tz)
            .stream()
            .map(Mappers::toDto)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  @Operation(
      summary = "Takings by member of staff",
      description =
          "What each cashier rang up, what they discounted, and their average basket, biggest taker"
              + " first. Read from the POS transaction journal, so this is in-store only — an"
              + " online order has no cashier, and these totals will not add up to the sales"
              + " summary for that reason. UNATTRIBUTED buckets journal entries naming nobody"
              + " rather than dropping them.")
  @APIResponse(responseCode = "200", description = "One row per cashier, biggest taker first")
  @APIResponse(
      responseCode = "400",
      description = "Unparseable timestamp or storeId, or from is not before to")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER or MANAGER")
  @GET
  @Path("/sales-by-staff")
  public Response salesByStaff(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    List<SalesByStaffRowResponse> rows =
        svc
            .salesByStaff(
                ctx.requireTenantId(),
                Parsing.optionalUuid(storeId, "storeId"),
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                clamped)
            .stream()
            .map(Mappers::toDto)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }
}
