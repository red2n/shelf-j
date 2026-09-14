package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.UnitPriceGapsResponse;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Where unit prices cannot be shown (03.13). Under {@code /admin/}, so management only by path. */
@Path("/admin/unit-pricing")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Unit pricing")
public class UnitPricingResource {

  @Inject PricingService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Priced items with no unit price",
      description =
          "Variants with a price in force and no declared measure, so no unit price can be shown,"
              + " with whether a unit price is law for this business today. Declared in product-svc:"
              + " the variant's net content and unit.")
  @APIResponse(responseCode = "200", description = "The gaps, by variant")
  @GET
  @Path("/gaps")
  public ApiResponse<UnitPriceGapsResponse> gaps() {
    return ApiResponse.ok(Mappers.toUnitPriceGaps(service.unitPriceGaps(ctx.requireTenantId())));
  }
}
