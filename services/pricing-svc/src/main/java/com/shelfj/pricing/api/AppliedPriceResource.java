package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.PriceHistoryResponse;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.AppliedPriceService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The applied-price ledger (03.12): what a variant was offered at, and from when. Under {@code
 * /admin/}, so management only by path; read-only, because the ledger is append-only.
 */
@Path("/admin/prices")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Prior price")
public class AppliedPriceResource {

  @Inject AppliedPriceService service;
  @Inject com.shelfj.pricing.service.PricingService pricing;
  @Inject TenantContext ctx;

  @Operation(
      summary = "A variant's applied-price history",
      description =
          "Every change in what a shopper was offered for the variant, on each channel and at each"
              + " store a promotion is scoped to, newest first: the price with VAT and promotions,"
              + " the regular price, the promotion, from when, and what caused it. The prior price"
              + " of a reduction is read from here. pending counts evaluations not yet recorded.")
  @APIResponse(responseCode = "200", description = "The history")
  @APIResponse(responseCode = "400", description = "variantId is not a UUID")
  @GET
  @Path("/history")
  public ApiResponse<PriceHistoryResponse> history(
      @QueryParam("variantId") String variantId, @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID variant = Parsing.uuid(variantId, "variantId");
    return ApiResponse.ok(
        new PriceHistoryResponse(
            variant,
            service.pending(tenantId),
            service.history(tenantId, variant, limit == null ? 100 : limit).stream()
                .map(Mappers::toAppliedPrice)
                .toList()));
  }

  @Operation(
      summary = "The reductions on offer, and whether each may be announced",
      description =
          "Every price on offer below its regular price on a channel, as the applied-price ledger"
              + " records it, with its prior price — the lowest of the 30 days before (Directive"
              + " 98/6/EC art.6a) — its status, and whether it may be announced as a reduction."
              + " Where the law binds, anything but ANNOUNCEABLE keeps the was price off labels,"
              + " the storefront and the till, and keeps item promotions off the storefront banner.")
  @APIResponse(responseCode = "200", description = "The reductions")
  @APIResponse(responseCode = "400", description = "channel is not ONLINE or POS")
  @GET
  @Path("/reductions")
  public ApiResponse<com.shelfj.pricing.dto.Dtos.ReductionsResponse> reductions(
      @QueryParam("channel") String channel) {
    UUID tenantId = ctx.requireTenantId();
    String key =
        channel == null || channel.isBlank()
            ? "ONLINE"
            : channel.trim().toUpperCase(java.util.Locale.ROOT);
    if (!"ONLINE".equals(key) && !"POS".equals(key)) {
      throw com.shelfj.web.ApiException.badRequest(
          "PRICING_CHANNEL_INVALID", "channel must be ONLINE or POS");
    }
    return ApiResponse.ok(
        new com.shelfj.pricing.dto.Dtos.ReductionsResponse(
            key,
            service.pending(tenantId),
            pricing.reductions(tenantId, key).stream().map(Mappers::toReduction).toList()));
  }
}
