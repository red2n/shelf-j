package com.shelfj.order.api;

import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #43 — POSLog / transaction journal. Append-only log of completed POS transactions.
 *
 * <p>Read side only. The <b>write</b> lives on {@link PosLogWriteResource} at {@code /pos/log},
 * because everything under {@code /admin/} is gated to management roles — so the cashier who
 * completed the sale could not journal it, and no client ever called this. A journal its own author
 * is locked out of records nothing.
 */
@RequestScoped
@Path("/admin/pos-log")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "POS Transaction Log")
public class PosLogResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "List POSLog entries",
      description =
          "Append-only POS transaction journal, optionally filtered by store. Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of POSLog entries")
  @GET
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    int clamped = Cursor.clampLimit(limit);
    var page = svc.listPosLog(ctx.requireTenantId(), storeId, after, clamped);
    var entries = page.entries().stream().map(Mappers::toDto).toList();
    return Response.ok(
            ApiResponse.ok(entries, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @Operation(
      summary = "List POSLog entries for an order",
      description = "All POSLog entries recorded against the given order.")
  @APIResponse(responseCode = "200", description = "List of POSLog entries")
  @GET
  @Path("/orders/{orderId}")
  public Response byOrder(@PathParam("orderId") UUID orderId) {
    var entries =
        svc.getPosLogByOrder(ctx.requireTenantId(), orderId).stream().map(Mappers::toDto).toList();
    return Response.ok(ApiResponse.ok(entries)).build();
  }
}
