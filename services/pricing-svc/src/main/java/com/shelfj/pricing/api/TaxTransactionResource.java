package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.RecordTaxTransactionRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiException;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** POSLog-compatible tax transaction journal per HMRC VAT Notice 700. */
@RequestScoped
@Path("/tax-transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tax Transactions")
public class TaxTransactionResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Record a tax transaction",
      description =
          "Appends a POSLog-compatible tax transaction journal entry (net/VAT/gross per order"
              + " line) per HMRC VAT Notice 700.")
  @APIResponse(responseCode = "201", description = "Tax transaction recorded")
  @POST
  public Response record(RecordTaxTransactionRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.recordTaxTransaction(req, ctx))))
        .build();
  }

  @Operation(
      summary = "List tax transactions for an order",
      description = "All tax transaction journal entries recorded for the given order.")
  @APIResponse(responseCode = "200", description = "List of tax transactions")
  @APIResponse(responseCode = "400", description = "orderId query param missing")
  @GET
  public Response listByOrder(@QueryParam("orderId") UUID orderId) {
    if (orderId == null)
      throw ApiException.badRequest("PRICING_MISSING_ORDER_ID", "orderId query param required");
    return Response.ok(
            ApiResponse.ok(
                svc.listTaxTransactionsByOrder(ctx, orderId).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
