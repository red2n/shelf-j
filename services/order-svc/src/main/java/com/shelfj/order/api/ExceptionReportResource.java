package com.shelfj.order.api;

import com.shelfj.order.domain.Domain.ExceptionGrouping;
import com.shelfj.order.dto.Dtos.ExceptionReportResponse;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
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
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Staff exception report — the loss-prevention view of who is discounting, voiding and opening the
 * drawer without a sale.
 *
 * <p>Deliberately under {@code /admin/} so {@code AdminAuthorizationFilter} gates it by path rather
 * than by a role check written into each method. That is the difference that produced SJ-D10: a
 * method added to this class later cannot be left open by someone forgetting a line.
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reports")
public class ExceptionReportResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Discounts granted, sales voided and no-sale drawer opens over a period, heaviest first.
   *
   * <p>Each row carries the journalled POS sales for its group so the counts can be read as a rate.
   * Check {@code journalCoverage} first: when it is false nothing journalled any sale in the
   * period, every sales figure is zero, and the counts have no denominator to be judged against.
   *
   * @param storeId restrict to one store, or {@code null}
   * @param from inclusive start as an ISO-8601 instant, or {@code null}
   * @param to exclusive end as an ISO-8601 instant, or {@code null}
   * @param groupBy group by the staff member responsible or by store
   * @return one row per group, most exceptions first
   * @throws com.shelfj.web.ApiException {@code 400} for an unknown {@code groupBy}, an unparseable
   *     timestamp, or a storeId that is not a UUID; {@code 403} when the caller is not OWNER or
   *     MANAGER
   */
  @Operation(
      summary = "Staff exception report",
      description =
          "Discounts granted, sales voided and no-sale drawer opens over a period, grouped by the"
              + " member of staff responsible or by store, heaviest first. Each row also carries"
              + " the journalled POS sales for that group so the counts can be read as a rate."
              + " Check `journalCoverage` first: when it is false nothing journalled any sale in"
              + " the period and every sales figure is zero, so the counts have no denominator.")
  @APIResponse(responseCode = "200", description = "One row per group, most exceptions first")
  @APIResponse(
      responseCode = "400",
      description = "Unknown groupBy, unparseable timestamp, or storeId is not a UUID")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER or MANAGER")
  @GET
  @Path("/exceptions")
  public Response exceptions(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("groupBy") String groupBy) {
    var rows =
        svc.exceptionReport(
            ctx.requireTenantId(),
            Parsing.optionalUuid(storeId, "storeId"),
            Parsing.optionalInstant(from, "from"),
            Parsing.optionalInstant(to, "to"),
            grouping(groupBy));
    // Coverage is derived from the rows rather than counted separately: if no group journalled a
    // single sale, there is no denominator and saying so is more useful than showing zeroes.
    boolean coverage = rows.stream().anyMatch(r -> r.sales() > 0);
    return Response.ok(
            ApiResponse.ok(
                new ExceptionReportResponse(rows.stream().map(Mappers::toDto).toList(), coverage),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /** Defaults to ACTOR: "who" is the question this report exists to answer. */
  private static ExceptionGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return ExceptionGrouping.ACTOR;
    try {
      return ExceptionGrouping.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // Cause preserved: the same shape the inventory report resources use, so a
      // malformed grouping is a 400 without discarding what produced it.
      throw new ApiException(
          400,
          "ORDER_INVALID_GROUPING",
          "groupBy must be ACTOR or STORE - got: " + raw,
          java.util.List.of(),
          e);
    }
  }
}
