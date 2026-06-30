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

@RequestScoped
@Path("/goods-receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GoodsReceiptResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @POST
  public Response receive(
      @HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      CreateGoodsReceiptRequest req) {
    Validations.validate(req);
    var gr = svc.receiveGoods(req, ctx, idempotencyKey);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gr, List.of()))).build();
  }

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
