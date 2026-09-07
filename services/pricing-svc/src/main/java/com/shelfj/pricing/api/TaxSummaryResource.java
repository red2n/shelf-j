package com.shelfj.pricing.api;

import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The tax summary report — the fourth and last of the reports named in the reporting gap analysis.
 *
 * <p>Lives under {@code /admin/} deliberately. {@code AdminAuthorizationFilter} gates every {@code
 * /admin/} path to PLATFORM_ADMIN / OWNER / MANAGER, which is the right audience for a tenant's tax
 * position, and gating it by construction means a future method added to this class cannot be left
 * open by forgetting a role check.
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Tax Summary")
public class TaxSummaryResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "VAT collected over a period, grouped",
      description =
          "The working behind the VAT return's single figures. Group by CODE to see which rate"
              + " bands the VAT is made of, by STORE to compare sites, or by MONTH to see a rate"
              + " change or a seasonal shift. Exempt supplies are reported as their own rows,"
              + " because the return counts their net in Box 6 but their VAT nowhere. Totals"
              + " reconcile: totals.netAmount is Box 6 and totals.outputVat is Box 1, over the same"
              + " rows and the same period.")
  @APIResponse(responseCode = "200", description = "Grouped rows plus reconciling totals")
  @APIResponse(
      responseCode = "400",
      description =
          "from/to missing, not ISO-8601 instants, or from is not before to; unknown groupBy;"
              + " storeId not a UUID")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER, MANAGER or PLATFORM_ADMIN")
  @GET
  @Path("/tax-summary")
  public Response taxSummary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("groupBy") String groupBy) {
    if (from == null || from.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_FROM", "from query param required (ISO-8601)");
    if (to == null || to.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_TO", "to query param required (ISO-8601)");
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.taxSummary(ctx, from, to, storeId, groupBy)),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
