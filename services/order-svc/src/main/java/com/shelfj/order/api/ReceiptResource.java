package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.GenerateReceiptRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
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
import java.util.UUID;

/**
 * Gap #44 — Receipt / e-journal printing. Append-only log of receipt generation events (print and
 * email). Does not render the receipt itself — the frontend renders from order data.
 */
@RequestScoped
@Path("/admin/orders/{orderId}/receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReceiptResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @POST
  public Response generate(@PathParam("orderId") UUID orderId, GenerateReceiptRequest req) {
    Validations.validate(req);
    var receipt = svc.generateReceipt(ctx.requireTenantId(), orderId, req);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(receipt))).build();
  }

  @GET
  public Response list(@PathParam("orderId") UUID orderId) {
    var receipts =
        svc.listReceipts(ctx.requireTenantId(), orderId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(receipts)).build();
  }
}
