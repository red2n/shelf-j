package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.CreateGoodsReceiptRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("/goods-receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Goods Receipts")
public class GoodsReceiptResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Record a goods receipt",
      description =
          "Records goods received against a SUBMITTED purchase order and publishes GoodsReceived."
              + " Supports Idempotency-Key to make retried receipts safe.")
  @APIResponse(responseCode = "201", description = "Goods receipt recorded")
  @APIResponse(
      responseCode = "400",
      description = "Purchase order is not SUBMITTED, or the receipt has no lines")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  public Response receive(
      @HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CreateGoodsReceiptRequest req) {
    Validations.validate(req);
    var gr = svc.receiveGoods(req, ctx, idempotencyKey);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gr, List.of()))).build();
  }

  @Operation(
      summary = "List goods receipts for a purchase order",
      description = "Requires ?poId=<purchase order id>.")
  @APIResponse(responseCode = "400", description = "poId query param required")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  public Response listByPo(@QueryParam("poId") UUID poId) {
    if (poId == null)
      throw ApiException.badRequest("PURCHASE_MISSING_PO_ID", "poId query param required");
    return Response.ok(
            ApiResponse.ok(
                svc.listGoodsReceipts(ctx, poId).stream()
                    .map(gr -> Mappers.toDto(gr, List.of()))
                    .toList()))
        .build();
  }
}
