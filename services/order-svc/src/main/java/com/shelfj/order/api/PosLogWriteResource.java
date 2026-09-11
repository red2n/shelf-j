package com.shelfj.order.api;

import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 * The write half of the POS transaction journal, on the till's own path.
 *
 * <p>It used to sit with the read half under {@code /admin/pos-log}, which {@code
 * AdminAuthorizationFilter} gates to management roles — so the cashier who took the sale could not
 * journal it, and unsurprisingly nothing ever called it. The table has been empty for the life of
 * the product, which is also why the staff exception report had no denominator: nothing recorded
 * who rang up a sale.
 *
 * <p>Moving it here matches where every other cashier-facing endpoint already lives ({@code
 * /pos/no-sale}, {@code /pos/parked-sales}) and lets the filter gate it correctly by path. Nothing
 * breaks: no client was calling the old path, because no client could.
 */
@RequestScoped
@Path("/pos/log")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "POS Transaction Log")
public class PosLogWriteResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Journal a completed POS sale",
      description =
          "Appends the transaction journal entry for a POS order. Called by the till right after"
              + " the sale completes. Idempotent on the order: a retry, or an offline sale replayed"
              + " later, returns the existing entry rather than failing.")
  @APIResponse(responseCode = "201", description = "Journalled, or already was")
  @APIResponse(responseCode = "400", description = "Order is not a POS-channel order")
  @APIResponse(responseCode = "403", description = "Caller lacks a POS-eligible role")
  @APIResponse(responseCode = "404", description = "Order not found")
  @POST
  @Path("/orders/{orderId}")
  public Response record(@PathParam("orderId") UUID orderId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    var entry = svc.recordPosLog(ctx.requireTenantId(), orderId, ctx.userId());
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(entry), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
