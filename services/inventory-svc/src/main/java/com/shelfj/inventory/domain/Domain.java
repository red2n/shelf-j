package com.shelfj.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for inventory. Quantities are BigDecimal (exact). */
public final class Domain {

  private Domain() {}

  public record Batch(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String batchNo,
      BigDecimal receivedQty,
      BigDecimal remainingQty,
      BigDecimal costPrice,
      LocalDate expiryDate,
      Instant createdAt,
      String status,
      String materialStatus,
      String materialStatusReason) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPLETED = "DEPLETED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String MATERIAL_AVAILABLE = "AVAILABLE";
    public static final String MATERIAL_QUARANTINE = "QUARANTINE";
    public static final String MATERIAL_INSPECTION = "INSPECTION";
    public static final String MATERIAL_DAMAGED = "DAMAGED";
    public static final String MATERIAL_RECALLED = "RECALLED";
  }

  /** Stock level rollup for a (store, variant). */
  public record Level(
      UUID storeId, UUID variantId, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}

  public record Reservation(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      String status,
      Instant expiresAt,
      Instant createdAt) {
    public static final String HELD = "HELD";
    public static final String CONSUMED = "CONSUMED";
    public static final String RELEASED = "RELEASED";
  }

  public record Movement(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String type,
      BigDecimal qty,
      String refType,
      UUID refId,
      Instant createdAt) {}

  public record Threshold(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal threshold,
      BigDecimal maxQty) {}

  /** Replenishment suggestion produced by the min-max planning engine. */
  public record Suggestion(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal availableQty,
      BigDecimal minQty,
      BigDecimal maxQty,
      BigDecimal suggestedQty,
      String status,
      Instant createdAt,
      Instant resolvedAt) {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ORDERED = "ORDERED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  /** Aggregated demand bucket (Gap #7). period = date_trunc(bucketType, SALE movements). */
  public record DemandBucket(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      LocalDate bucketDate,
      String bucketType,
      BigDecimal demandQty,
      int movementCount,
      Instant computedAt) {
    public static final String BUCKET_DAY = "DAY";
    public static final String BUCKET_WEEK = "WEEK";
    public static final String BUCKET_MONTH = "MONTH";
  }

  /** Movement types (stock_movements.type). qty is signed (+in / -out). */
  public static final class MoveType {
    private MoveType() {}

    public static final String RECEIVE = "RECEIVE";
    public static final String SALE = "SALE";
    public static final String ADJUST = "ADJUST";
    public static final String TRANSFER = "TRANSFER";
    public static final String RETURN = "RETURN";
    public static final String RESERVE = "RESERVE";
    public static final String RELEASE = "RELEASE";
  }
}
