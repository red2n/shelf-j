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

  /** An individual tracked unit — used for high-value / warranty items (Gap #3). */
  public record SerialNumber(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String serialNo,
      String status,
      Instant receivedAt,
      Instant soldAt) {
    public static final String IN_STOCK = "IN_STOCK";
    public static final String RESERVED = "RESERVED";
    public static final String SOLD = "SOLD";
    public static final String RETURNED = "RETURNED";
    public static final String LOST = "LOST";
    public static final String DAMAGED = "DAMAGED";
  }

  /** One entry in the genealogy log for a serial number (append-only). */
  public record SerialMovement(
      UUID id,
      UUID tenantId,
      UUID serialId,
      String fromStatus,
      String toStatus,
      String refType,
      UUID refId,
      Instant createdAt) {}

  /** A request to move stock from one store/zone to another within the same tenant (Gap #5). */
  public record MoveOrder(
      UUID id,
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String fromZone,
      String toZone,
      String notes,
      String status,
      Instant createdAt,
      Instant pickedAt) {
    public static final String DRAFT = "DRAFT";
    public static final String OPEN = "OPEN";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
  }

  /** One SKU line on a move order. */
  public record MoveOrderLine(
      UUID id,
      UUID tenantId,
      UUID moveOrderId,
      UUID variantId,
      BigDecimal requestedQty,
      BigDecimal pickedQty) {}

  /**
   * Inter-store transfer order (Gap #6). DIRECT ships and receives atomically; INTRANSIT is
   * two-phase.
   */
  public record TransferOrder(
      UUID id,
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String transferType,
      String status,
      String notes,
      Instant createdAt,
      Instant shippedAt,
      Instant receivedAt) {
    public static final String TYPE_DIRECT = "DIRECT";
    public static final String TYPE_INTRANSIT = "INTRANSIT";
    public static final String PENDING = "PENDING";
    public static final String SHIPPED = "SHIPPED";
    public static final String RECEIVED = "RECEIVED";
    public static final String CANCELLED = "CANCELLED";
  }

  /** One SKU line on a transfer order. */
  public record TransferOrderLine(
      UUID id,
      UUID tenantId,
      UUID transferOrderId,
      UUID variantId,
      BigDecimal requestedQty,
      BigDecimal shippedQty,
      BigDecimal receivedQty) {}

  /**
   * Safety stock parameters + last computed result for a (store, variant) pair (Gap #8). method MAD
   * uses Mean Absolute Deviation from demand buckets. method USER_DEFINED uses a user-supplied
   * percentage of avg demand over lead time.
   */
  public record SafetyStockParams(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String method,
      int leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct,
      BigDecimal safetyStockQty,
      Instant computedAt,
      Instant createdAt) {
    public static final String METHOD_MAD = "MAD";
    public static final String METHOD_USER_DEFINED = "USER_DEFINED";
  }

  /** Audit record of one ABC compile run (Gap #9). */
  public record AbcCompileRun(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String criteria,
      BigDecimal thresholdA,
      BigDecimal thresholdAB,
      int itemsCompiled,
      Instant compiledAt) {
    public static final String CRITERIA_VALUE = "VALUE";
    public static final String CRITERIA_VELOCITY = "VELOCITY";
  }

  /** Per-(store, variant) ABC class assignment produced by a compile run (Gap #9). */
  public record AbcAssignment(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID runId,
      String abcClass,
      BigDecimal score,
      int rank,
      Instant assignedAt) {}

  /** Cycle count header — defines scope, tolerance, and lifecycle status (Gap #10). */
  public record CycleCountHeader(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String abcClasses,
      BigDecimal tolerancePct,
      String status,
      Instant createdAt,
      Instant completedAt) {
    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String ADJUSTED = "ADJUSTED";
    public static final String CLOSED = "CLOSED";
  }

  /** One (store, variant) line within a cycle count (Gap #10). */
  public record CycleCountLine(
      UUID id,
      UUID tenantId,
      UUID headerId,
      UUID storeId,
      UUID variantId,
      BigDecimal systemQty,
      BigDecimal countedQty,
      BigDecimal variance,
      BigDecimal variancePct,
      String status,
      Instant countedAt) {
    public static final String OPEN = "OPEN";
    public static final String COUNTED = "COUNTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String ADJUSTED = "ADJUSTED";
  }

  // ── Gap #11: Lot Genealogy ───────────────────────────────────────────────

  public record LotGenealogyLink(
      UUID id,
      UUID tenantId,
      UUID parentBatchId,
      UUID childBatchId,
      BigDecimal qty,
      String relationType,
      String notes,
      Instant createdAt) {
    public static final String SPLIT = "SPLIT";
    public static final String MERGE = "MERGE";
    public static final String TRANSFORM = "TRANSFORM";
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
