package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.AgeVerificationResponse;
import com.shelfj.order.dto.Dtos.AgeVerificationSummaryResponse;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The age-check register: what a licensing officer asks to see. Management-only, like every {@code
 * /admin/} read that is not a shop-floor surface — a cashier records checks ({@link
 * AgeCheckResource}), a manager answers for them.
 */
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Age checks")
@Path("/admin/pos/age-checks")
public class AgeCheckAdminResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * The register, newest first.
   *
   * @param store one store, or all
   * @param outcome PASSED or REFUSED, or all
   * @param from inclusive ISO-8601 lower bound
   * @param to exclusive ISO-8601 upper bound
   * @param after cursor from the previous page
   * @param limit page size, 1..100
   * @return the page
   */
  @Operation(
      summary = "The age-check register",
      description =
          "Every check made at the till, newest first, filterable by store, outcome and period."
              + " Management-only.")
  @APIResponse(responseCode = "200", description = "A page of checks")
  @APIResponse(responseCode = "400", description = "A malformed cursor, date or outcome")
  @GET
  public ApiResponse<List<AgeVerificationResponse>> list(
      @QueryParam("store") String store,
      @QueryParam("outcome") String outcome,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page =
        svc.listAgeChecks(
            ctx.requireTenantId(),
            blank(store),
            outcome,
            instant(from, "from"),
            instant(to, "to"),
            after,
            Cursor.clampLimit(limit));
    return ApiResponse.ok(
        page.items().stream().map(Mappers::toDto).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * The counts for a store and period.
   *
   * @param store one store, or the tenant
   * @param from inclusive ISO-8601 lower bound
   * @param to exclusive ISO-8601 upper bound
   * @return total, passed, refused, refusals by reason, checks by category
   */
  @Operation(
      summary = "Age-check summary",
      description =
          "How many checks, how many refusals and why — the first question an officer asks.")
  @APIResponse(responseCode = "200", description = "The counts")
  @GET
  @Path("/summary")
  public ApiResponse<AgeVerificationSummaryResponse> summary(
      @QueryParam("store") String store,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    return ApiResponse.ok(
        Mappers.toDto(
            svc.summariseAgeChecks(
                ctx.requireTenantId(), blank(store), instant(from, "from"), instant(to, "to"))),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static Instant instant(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "INVALID_DATE", name + " must be an ISO-8601 instant", List.of(), e);
    }
  }
}
