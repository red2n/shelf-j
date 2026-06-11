package com.shelfj.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
}
