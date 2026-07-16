package com.shelfj.reporting.api;

import com.shelfj.reporting.mapper.Mappers;
import com.shelfj.reporting.service.ReportingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * N4: sales revenue reporting, built from the OrderConfirmed / PaymentRefunded projection. {@code
 * from}/{@code to} are inclusive calendar dates (ISO {@code yyyy-MM-dd}); {@code to} covers the
 * whole day. tenant comes from the JWT.
 */
@Path("/admin/reports/sales")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sales Reports")
public class SalesReportResource {

  @Inject ReportingService service;
  @Inject TenantContext ctx;

  /** Gross / refunded / net revenue and order count, grouped by currency. */
  @Operation(
      summary = "Sales revenue summary",
      description =
          "Gross/refunded/net revenue and order count, grouped by currency, over the given"
              + " inclusive date range. Optionally filtered by store and/or channel"
              + " (ONLINE/POS).")
  @APIResponse(responseCode = "200", description = "Sales summary rows, one per currency")
  @APIResponse(
      responseCode = "400",
      description = "from/to is not a valid yyyy-MM-dd date, or storeId is not a valid UUID")
  @GET
  @Path("/summary")
  public ApiResponse<Object> summary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel) {
    var rows =
        service.salesSummary(
            ctx.tenantId(), fromDay(from), toDay(to), optUuid(storeId), blankToNull(channel));
    return ApiResponse.ok(Mappers.toSalesSummaryReport(rows));
  }

  /** Daily revenue buckets (per currency), newest day first. */
  @Operation(
      summary = "Daily sales revenue buckets",
      description =
          "Daily revenue buckets (per currency), newest day first, over the given inclusive date"
              + " range. Optionally filtered by store and/or channel (ONLINE/POS).")
  @APIResponse(responseCode = "200", description = "Daily sales rows")
  @APIResponse(
      responseCode = "400",
      description = "from/to is not a valid yyyy-MM-dd date, or storeId is not a valid UUID")
  @GET
  @Path("/by-day")
  public ApiResponse<Object> byDay(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel) {
    var rows =
        service.salesByDay(
            ctx.tenantId(), fromDay(from), toDay(to), optUuid(storeId), blankToNull(channel));
    return ApiResponse.ok(Mappers.toSalesByDayReport(rows));
  }

  private static Instant fromDay(String s) {
    return s == null || s.isBlank()
        ? null
        : Parsing.date(s, "from").atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /**
   * {@code to} is inclusive of the whole day, so the exclusive upper bound is the next midnight.
   */
  private static Instant toDay(String s) {
    return s == null || s.isBlank()
        ? null
        : Parsing.date(s, "to").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static UUID optUuid(String s) {
    return s == null || s.isBlank() ? null : UUID.fromString(s);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
