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

/** HMRC MTD VAT return endpoint: computes boxes 1-9 per VAT Notice 700 s.17. */
@RequestScoped
@Path("/vat-return")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "VAT Return")
public class VatReturnResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Compute the MTD VAT return for a period",
      description =
          "Computes HMRC Making Tax Digital VAT return boxes 1-9 for the given date range. Box 4"
              + " (input VAT) and boxes 7-9 remain zero until purchase-svc's data feeds them.")
  @APIResponse(responseCode = "200", description = "Computed VAT return boxes 1-9")
  @APIResponse(
      responseCode = "400",
      description = "from/to query params missing, or from is not before to")
  @GET
  public Response compute(@QueryParam("from") String from, @QueryParam("to") String to) {
    if (from == null || from.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_FROM", "from query param required (ISO-8601)");
    if (to == null || to.isBlank())
      throw ApiException.badRequest("PRICING_MISSING_TO", "to query param required (ISO-8601)");
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.computeVatReturn(ctx, from, to)))).build();
  }
}
