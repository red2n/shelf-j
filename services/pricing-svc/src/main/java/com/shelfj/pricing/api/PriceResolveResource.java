package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.ResolvePriceRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Resolve the effective GBP price + VAT breakdown for a given variant, channel, and quantity. */
@RequestScoped
@Path("/prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceResolveResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @POST
  @Path("/resolve")
  public Response resolve(ResolvePriceRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.resolvePrice(req, ctx)))).build();
  }
}
