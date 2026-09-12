package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos;
import com.shelfj.pricing.dto.Dtos.QuoteBasketRequest;
import com.shelfj.pricing.dto.Dtos.RecordRedemptionsRequest;
import com.shelfj.pricing.dto.Dtos.RecordRedemptionsResponse;
import com.shelfj.pricing.dto.Dtos.ResolvePriceBatchRequest;
import com.shelfj.pricing.dto.Dtos.ResolvePriceBatchResponse;
import com.shelfj.pricing.dto.Dtos.ResolvePriceRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
  @Inject com.shelfj.pricing.service.MarkdownService markdowns;
  @Inject TenantContext ctx;

  /**
   * The effective price for one variant, for a product page.
   *
   * <p>Item-level promotions only: basket-level and coupon promotions are excluded, because a
   * spend-threshold price shown against a single item advertises a total the shopper will not be
   * charged. Use the basket quote at checkout.
   *
   * @param req the variant, optional quantity, channel and store
   * @return the unit price with its VAT code, rate, amount and gross
   * @throws com.shelfj.web.ApiException {@code 404} when no active price is configured
   */
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
   *
   * <p>Each line is still priced independently, so this applies no basket-level or coupon promotion
   * either.
   *
   * @param req the lines to price
   * @return one resolved price per line, in the order supplied
   * @throws com.shelfj.web.ApiException {@code 404} as soon as any line has no active price — the
   *     whole call fails rather than returning a partial list
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

  /**
   * Prices a whole basket: promotions, coupons and VAT together.
   *
   * <p>The only path that can apply basket-level rules — spend thresholds, basket percentages, BOGO
   * — because they need the whole basket to be about. VAT is computed per line on the discounted
   * amount, with basket-level discount apportioned by value first, so relief lands on the right
   * rate band.
   *
   * <p>Quoting does not spend a coupon; {@code /redemptions} does that at checkout.
   *
   * @param req the lines, channel, store, customer and any coupon codes presented
   * @return the priced lines with subtotal, discounts, VAT, total, promotions applied, and any
   *     coupons rejected with a reason
   * @throws com.shelfj.web.ApiException {@code 400} when a line's quantity is not positive; {@code
   *     404} when a variant has no active price
   */
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

  /**
   * What a scanned reduced-price sticker means at the till (05.4).
   *
   * @param code the sticker's thirteen digits
   * @return the live markdown behind it
   */
  @Operation(
      summary = "What a reduced-price sticker means",
      description =
          "The live markdown behind a scanned sticker: the product, the reduced price, the date"
              + " and how many packs are left at that price. 404 for a code no live sticker"
              + " carries; 409 when the batch is past its date or every stickered pack has sold."
              + " Any staff role — this is what the till calls.")
  @APIResponse(responseCode = "200", description = "The markdown behind the sticker")
  @APIResponse(responseCode = "404", description = "No live sticker carries this code")
  @APIResponse(responseCode = "409", description = "Expired, or sold out at this price")
  @GET
  @Path("/markdown-labels/{code}")
  public Response markdownLabel(@PathParam("code") String code) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    return Response.ok(
            ApiResponse.ok(Mappers.toLabelDto(markdowns.lookupLabel(ctx.requireTenantId(), code))))
        .build();
  }

  /**
   * Records what an order sold at reduced prices (05.4), called by order-svc after checkout.
   *
   * @param req the order and its markdown lines
   * @return how many were recorded now
   */
  @Operation(
      summary = "Record what an order sold at reduced prices",
      description =
          "Called by order-svc once an order is placed, so a markdown can run out and a report can"
              + " say what reducing to clear cost. Once per markdown and order.")
  @APIResponse(responseCode = "200", description = "Recorded")
  @POST
  @Path("/markdown-redemptions")
  public Response recordMarkdownRedemptions(Dtos.RecordMarkdownRedemptionsRequest req) {
    com.shelfj.web.Validations.validate(req);
    java.util.Map<java.util.UUID, java.math.BigDecimal> byMarkdown =
        new java.util.LinkedHashMap<>();
    for (var l : req.lines()) {
      byMarkdown.merge(
          Parsing.uuid(l.markdownId(), "markdownId"), l.qty(), java.math.BigDecimal::add);
    }
    int written =
        markdowns.recordRedemptions(
            ctx.requireTenantId(), Parsing.uuid(req.orderId(), "orderId"), byMarkdown);
    return Response.ok(ApiResponse.ok(java.util.Map.of("recorded", written))).build();
  }

  /**
   * Records that an order used the promotions a quote applied.
   *
   * <p>Separate from quoting on purpose: a basket is quoted many times as a shopper adds items, and
   * a coupon must not be spent by looking at it. Only checkout calls this, once the order exists to
   * attribute the redemption to. A replay records nothing.
   *
   * @param req the order, customer, applied promotions and currency
   * @return how many redemptions this call actually recorded
   */
  @Operation(
      summary = "Record that an order used these promotions",
      description =
          "Spends the usage caps on the promotions an order actually applied. Separate from"
              + " quoting on purpose: a basket is quoted on every change a shopper makes, and a"
              + " coupon must not be spent by being looked at — only a placed order spends one."
              + " Idempotent on (tenant, promotion, order), so a retried checkout or a replayed"
              + " offline sale cannot burn a second use. recorded=0 means every redemption was"
              + " already on file, which is a successful replay rather than a failure.")
  @APIResponse(responseCode = "200", description = "How many redemptions were newly recorded")
  @POST
  @Path("/redemptions")
  public Response recordRedemptions(RecordRedemptionsRequest req) {
    Validations.validate(req);
    int recorded =
        svc.recordRedemptions(
            ctx,
            com.shelfj.web.Parsing.uuid(req.orderId(), "orderId"),
            com.shelfj.web.Parsing.optionalUuid(req.customerId(), "customerId"),
            req.appliedPromotions(),
            req.currency() == null ? "GBP" : req.currency());
    return Response.ok(
            ApiResponse.ok(
                new RecordRedemptionsResponse(recorded), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
