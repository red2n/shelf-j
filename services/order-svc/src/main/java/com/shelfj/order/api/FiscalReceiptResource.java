package com.shelfj.order.api;

import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The gapless legal receipt sequence.
 *
 * <p>Separate from {@link ReceiptResource}, which records how many times a document was printed or
 * emailed. This is the document itself: one per sale, numbered consecutively within a series and
 * period, never renumbered, never deleted.
 *
 * <p>Numbers are taken automatically when a sale is confirmed. These endpoints exist for the two
 * cases that need a human: reading the number back, and proving to an inspector that the sequence
 * has no holes.
 */
@RequestScoped
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fiscal receipts")
public class FiscalReceiptResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Issue the numbered receipt for a sale",
      description =
          "Idempotent: a second call returns the document already issued rather than allocating"
              + " another number, because a reprint is not a sale and two numbers for one sale is"
              + " how a day's takings get counted twice.\\n\\n"
              + "Normally unnecessary — the number is taken when the sale is confirmed. This is"
              + " the recovery path for a sale that completed while receipt issuance was failing,"
              + " and it is safe to call at any time.\\n\\n"
              + "Refused for a PENDING or CANCELLED order: numbering a basket that is never paid"
              + " for is where gaps come from.")
  @APIResponse(responseCode = "200", description = "The receipt, newly issued or already existing")
  @APIResponse(responseCode = "400", description = "The order is not a completed sale")
  @APIResponse(responseCode = "404", description = "Order not found")
  @POST
  @Path("/orders/{orderId}/fiscal-receipt")
  public Response issue(@PathParam("orderId") UUID orderId, @QueryParam("series") String series) {
    return Response.ok(
            ApiResponse.ok(svc.issueReceipt(ctx.requireTenantId(), orderId, series, ctx.userId())))
        .build();
  }

  @Operation(
      summary = "The receipt issued for a sale",
      description = "Its number, when it was issued, and whether the sale was later voided.")
  @APIResponse(responseCode = "200", description = "The receipt")
  @APIResponse(responseCode = "404", description = "No receipt has been issued for this sale")
  @GET
  @Path("/orders/{orderId}/fiscal-receipt")
  public Response get(@PathParam("orderId") UUID orderId) {
    return Response.ok(ApiResponse.ok(svc.receiptOf(ctx.requireTenantId(), orderId))).build();
  }

  @Operation(
      summary = "Every receipt in a series, in order",
      description =
          "The register a store keeps. series defaults to MAIN; period is the fiscal year, e.g."
              + " 2026.")
  @APIResponse(responseCode = "200", description = "Receipts by number")
  @GET
  @Path("/fiscal-receipts")
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("series") String series,
      @QueryParam("period") String period,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.receiptSeries(
                    ctx.requireTenantId(),
                    Parsing.uuid(storeId, "storeId"),
                    series,
                    requirePeriod(period),
                    limit)))
        .build();
  }

  @Operation(
      summary = "Prove the sequence has no holes",
      description =
          "The inspector's question, answered by the database rather than by assertion. Returns"
              + " the first and last numbers issued, how many were issued, how many the span"
              + " implies, and every gap with its range.\\n\\n"
              + "`intact: true` with an empty `gaps` array is the proof. A gap is not necessarily"
              + " fraud — it is the thing that has to be explained, which is why the range is"
              + " given rather than a count.")
  @APIResponse(responseCode = "200", description = "The audit")
  @GET
  @Path("/fiscal-receipts/audit")
  public Response audit(
      @QueryParam("storeId") String storeId,
      @QueryParam("series") String series,
      @QueryParam("period") String period) {
    Map<String, Object> result =
        svc.receiptAudit(
            ctx.requireTenantId(), Parsing.uuid(storeId, "storeId"), series, requirePeriod(period));
    return Response.ok(ApiResponse.ok(result)).build();
  }

  private static String requirePeriod(String period) {
    if (period == null || period.isBlank()) {
      return String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear());
    }
    return period.trim();
  }
}
