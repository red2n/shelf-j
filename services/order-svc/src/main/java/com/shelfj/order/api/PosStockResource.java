package com.shelfj.order.api;

import com.shelfj.order.domain.Domain.PosStockPosition;
import com.shelfj.order.dto.Dtos.PosStockPositionResponse;
import com.shelfj.order.repo.OrderRepository;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Gap #50 — SIM ↔ POS sync. Exposes the local stock-position projection to POS screens so cashiers
 * see live on-hand quantities without round-tripping to inventory-svc.
 *
 * <p>The projection is updated asynchronously from inventory-svc Kafka events (see {@link
 * com.shelfj.order.messaging.InventoryEventConsumer}). It is eventually consistent — not a
 * real-time guarantee — so it must not be used for reservation decisions.
 */
@RequestScoped
@Path("/admin/pos/stock-positions")
@Produces(MediaType.APPLICATION_JSON)
public class PosStockResource {

  @Inject OrderRepository repo;
  @Inject TenantContext ctx;

  @GET
  public Response list(
      @QueryParam("storeId") String storeIdStr,
      @QueryParam("variantId") String variantIdStr,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    int effectiveLimit = limit > 100 ? 100 : limit;
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = storeIdStr != null ? UUID.fromString(storeIdStr) : null;
    UUID variantId = variantIdStr != null ? UUID.fromString(variantIdStr) : null;
    List<PosStockPositionResponse> rows =
        repo.findStockPositions(tenantId, storeId, variantId, effectiveLimit).stream()
            .map(PosStockResource::toResponse)
            .toList();
    return Response.ok(ApiResponse.ok(rows)).build();
  }

  private static PosStockPositionResponse toResponse(PosStockPosition p) {
    return new PosStockPositionResponse(
        p.storeId().toString(),
        p.variantId().toString(),
        p.onHandQty().toPlainString(),
        p.updatedAt().toString());
  }
}
