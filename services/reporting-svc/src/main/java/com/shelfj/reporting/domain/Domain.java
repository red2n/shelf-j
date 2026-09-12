package com.shelfj.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain records for reporting-svc — the shapes its projections are read back as.
 *
 * <p>These are read models folded from other services' events, not source-of-truth entities, and
 * they never cross the HTTP boundary: {@link com.shelfj.reporting.mapper.Mappers} converts them to
 * the DTOs in {@link com.shelfj.reporting.dto.Dtos} first.
 */
public final class Domain {

  private Domain() {}

  /** Current on-hand projection per (tenant, store, variant). */
  public record InventoryProjection(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal onHand, Instant updatedAt) {}

  /** Single movement event used to aggregate demand stats. */
  public record MovementEvent(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String eventType,
      BigDecimal qtyChange,
      Instant occurredAt) {}

  /** Open transfer order line in transit (supply side of netting). */
  public record OpenSupplyLine(
      UUID id,
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      UUID variantId,
      BigDecimal qty,
      UUID eventId) {}

  /** Aggregated movement stat for one (store, variant, bucket). */
  public record MovementStat(
      UUID storeId, UUID variantId, String bucket, BigDecimal totalIn, BigDecimal totalOut) {}

  /** Sales totals for one currency over the queried window/filters. net = gross − refunded. */
  public record SalesSummary(String currency, long orders, BigDecimal gross, BigDecimal refunded) {
    /**
     * Net takings for the window.
     *
     * @return gross less refunded
     */
    public BigDecimal net() {
      return gross.subtract(refunded);
    }
  }

  /** Sales totals bucketed by day (and currency). net = gross − refunded. */
  public record SalesDayStat(
      String day, String currency, long orders, BigDecimal gross, BigDecimal refunded) {
    /**
     * Net takings for the day.
     *
     * @return gross less refunded
     */
    public BigDecimal net() {
      return gross.subtract(refunded);
    }
  }
}
