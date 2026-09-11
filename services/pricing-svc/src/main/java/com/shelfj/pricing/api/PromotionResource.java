package com.shelfj.pricing.api;

import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The storefront's read of what is currently on offer.
 *
 * <p><b>Read only, and deliberately.</b> Creating and switching promotions moved to {@code
 * /admin/promotions} because this path is outside {@code /admin/} and the authorisation filter's
 * mutation tier therefore asked only for some staff role — a cashier could create a 100%-off
 * promotion (SJ-D36). This path stays open because a shopper legitimately needs to see the offers;
 * nothing here changes anything.
 */
@RequestScoped
@Path("/promotions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Promotions")
public class PromotionResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List active promotions",
      description = "All currently-active promotions for the tenant.")
  @APIResponse(responseCode = "200", description = "List of active promotions")
  @GET
  public Response list() {
    return Response.ok(
            ApiResponse.ok(svc.listActivePromotions(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
