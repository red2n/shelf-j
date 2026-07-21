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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #44 — Receipt / e-journal printing. Append-only log of receipt generation events (print and
 * email). Does not render the receipt itself — the frontend renders from order data.
 */
@RequestScoped
@Path("/admin/orders/{orderId}/receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Receipts")
public class ReceiptResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Generate a receipt record",
      description =
          "Records a print or email receipt-generation event for the order. For EMAIL, builds a"
              + " plain-text receipt and delivers it via notification-svc (SMTP when configured)"
              + " before writing the audit row. emailedTo is required for EMAIL receipts.")
  @APIResponse(responseCode = "201", description = "Receipt record created")
  @APIResponse(responseCode = "400", description = "emailedTo missing for an EMAIL receipt type")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(responseCode = "503", description = "notification-svc unavailable — email not sent")
  @POST
  public Response generate(@PathParam("orderId") UUID orderId, GenerateReceiptRequest req) {
    Validations.validate(req);
    var receipt = svc.generateReceipt(ctx.requireTenantId(), orderId, req, ctx);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(receipt))).build();
  }

  @Operation(
      summary = "List receipt records for an order",
      description = "All receipt-generation records (print/email) for the order.")
  @APIResponse(responseCode = "200", description = "List of receipt records")
  @APIResponse(responseCode = "404", description = "Order not found")
  @GET
  public Response list(@PathParam("orderId") UUID orderId) {
    var receipts =
        svc.listReceipts(ctx.requireTenantId(), orderId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(receipts)).build();
  }
}
