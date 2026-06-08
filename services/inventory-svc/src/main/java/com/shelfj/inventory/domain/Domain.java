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
      String status) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPLETED = "DEPLETED";
    public static final String STATUS_EXPIRED = "EXPIRED";
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
      UUID id, UUID tenantId, UUID storeId, UUID variantId, BigDecimal threshold) {}

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
