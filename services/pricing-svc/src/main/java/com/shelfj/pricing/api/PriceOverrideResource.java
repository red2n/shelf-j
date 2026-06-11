package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.CreatePriceOverrideRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Gap #41 — POS price overrides. Append-only audit log of staff-approved ad-hoc price changes at
 * the point of sale. Requires STAFF or ADMIN role (enforced by AdminAuthorizationFilter).
 */
@RequestScoped
@Path("/admin/price-overrides")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceOverrideResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreatePriceOverrideRequest req) {
    Validations.validate(req);
    var override = svc.createPriceOverride(ctx, req);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(override))).build();
  }

  @GET
  public Response list(
      @QueryParam("storeId") String storeId, @QueryParam("variantId") String variantId) {
    var overrides =
        svc.listPriceOverrides(ctx, storeId, variantId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(overrides)).build();
  }
}
