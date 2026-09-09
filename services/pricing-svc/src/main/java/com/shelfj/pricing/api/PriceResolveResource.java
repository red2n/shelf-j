package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.QuoteBasketRequest;
import com.shelfj.pricing.dto.Dtos.ResolvePriceBatchRequest;
import com.shelfj.pricing.dto.Dtos.ResolvePriceBatchResponse;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Resolve the effective GBP price + VAT breakdown for a given variant, channel, and quantity. */
@RequestScoped
@Path("/prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Resolution")
public class PriceResolveResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Resolve the effective price for a variant",
      description =
          "Resolves unit price, applicable promotion, VAT code/rate/amount, and total-with-VAT"
              + " for a single variant/channel/quantity.")
  @APIResponse(responseCode = "200", description = "Resolved price and VAT breakdown")
  @APIResponse(responseCode = "404", description = "No active price configured for the variant")
  @POST
  @Path("/resolve")
  public Response resolve(ResolvePriceRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.resolvePrice(req, ctx)))).build();
  }

  /**
   * Batch form of {@link #resolve} — one call for every line in an order instead of one per line.
   */
  @Operation(
      summary = "Resolve effective prices for multiple lines",
      description =
          "Batch form of price resolution — one call for every line in an order instead of one"
              + " HTTP round trip per line. Results are returned in the same order as the request.")
  @APIResponse(responseCode = "200", description = "Resolved prices, one per input line")
  @APIResponse(responseCode = "404", description = "No active price configured for a variant")
  @POST
  @Path("/resolve-batch")
  public Response resolveBatch(ResolvePriceBatchRequest req) {
    Validations.validate(req);
    var results = svc.resolvePrices(req.lines(), ctx).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(new ResolvePriceBatchResponse(results))).build();
  }

  @Operation(
      summary = "Price a whole basket",
      description =
          "Resolves every line, then runs the promotion engine over the basket as a unit and"
              + " returns the itemised result. Use this at checkout in preference to"
              + " /resolve-batch, which prices each line independently and therefore cannot see a"
              + " spend threshold, a basket percentage or a buy-one-get-one — those rules need the"
              + " order total to exist before they mean anything."
              + " Coupon codes are matched case-insensitively; any that do not apply come back in"
              + " rejectedCoupons with a reason (NO_SUCH_COUPON, NOT_APPLICABLE, COUPON_EXHAUSTED,"
              + " COUPON_LIMIT_REACHED) rather than being silently ignored."
              + " Quoting never spends a coupon — a basket is quoted on every change, and the"
              + " redemption is recorded by the checkout once an order exists to attribute it to.")
  @APIResponse(responseCode = "200", description = "The priced basket")
  @APIResponse(responseCode = "400", description = "Malformed id or a non-positive quantity")
  @APIResponse(responseCode = "404", description = "No active price configured for a variant")
  @POST
  @Path("/quote")
  public Response quote(QuoteBasketRequest req) {
    Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(svc.quoteBasket(req, ctx), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
