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
      String materialStatusReason,
      String grade,
      UUID zoneId) {
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
      String reasonCode,
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

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────

  public record ReorderPointPlan(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      int leadTimeDays,
      java.math.BigDecimal orderingCost,
      java.math.BigDecimal holdingCostPct,
      java.math.BigDecimal unitCost,
      java.math.BigDecimal avgDailyDemand,
      java.math.BigDecimal rop,
      java.math.BigDecimal eoq,
      java.math.BigDecimal minOrderQty,
      java.math.BigDecimal maxOrderQty,
      java.math.BigDecimal lotMultiplier,
      Instant computedAt,
      Instant createdAt) {}

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────

  public record KanbanCard(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String kanbanType,
      String status,
      java.math.BigDecimal reorderQty,
      UUID sourceStoreId,
      String supplierRef,
      String notes,
      java.math.BigDecimal minOrderQty,
      java.math.BigDecimal maxOrderQty,
      java.math.BigDecimal lotMultiplier,
      Instant createdAt,
      Instant triggeredAt,
      Instant replenishedAt) {
    public static final String SUPPLIER = "SUPPLIER";
    public static final String INTER_ORG = "INTER_ORG";
    public static final String INTRA_ORG = "INTRA_ORG";
    public static final String PRODUCTION = "PRODUCTION";
    public static final String EMPTY = "EMPTY";
    public static final String TRIGGERED = "TRIGGERED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String REPLENISHED = "REPLENISHED";
  }

  // ── Gap #17: Costing ─────────────────────────────────────────────────────

  public record CostingMethod(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String method,
      java.math.BigDecimal averageCost,
      Instant updatedAt) {
    public static final String FIFO = "FIFO";
    public static final String AVERAGE = "AVERAGE";
  }

  public record AccountingPeriod(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String periodName,
      java.time.LocalDate periodDate,
      String status,
      Instant openedAt,
      Instant closedAt) {
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";
  }

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  public record ReasonCode(
      UUID id, UUID tenantId, String code, String description, boolean active, Instant createdAt) {}

  // ── Tier-1 Gap #22: Configurable transaction source types ────────────────

  public record TransactionSourceType(
      UUID id, UUID tenantId, String code, String description, boolean active, Instant createdAt) {}

  // ── Tier-1 Gap #23: Lot action (split / merge) ────────────────────────────

  public record LotAction(
      UUID id,
      UUID tenantId,
      String actionType,
      UUID sourceBatchId,
      UUID resultBatchId,
      java.math.BigDecimal qty,
      String notes,
      Instant createdAt) {
    public static final String SPLIT = "SPLIT";
    public static final String MERGE = "MERGE";
  }

  // ── Tier-1 Gap #26: Lot-specific UOM conversions ─────────────────────────

  public record LotUomConversion(
      UUID id,
      UUID tenantId,
      UUID batchId,
      String fromUom,
      String toUom,
      java.math.BigDecimal factor,
      String notes,
      Instant createdAt) {}

  // ── Tier-1 Gap #27: PAR level configs ────────────────────────────────────

  public record ParLevelConfig(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      java.math.BigDecimal parQty,
      String uom,
      String reviewCycle,
      Instant createdAt,
      Instant updatedAt) {
    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";
  }

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  public record ZoneGlMapping(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID zoneId,
      String nominalCode,
      String description,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  // ── Gap #38: Picking Rules ────────────────────────────────────────────────

  public record PickingRule(
      UUID id,
      UUID tenantId,
      String name,
      String strategy,
      String gradePreference,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String FIFO = "FIFO";
    public static final String FEFO = "FEFO";
    public static final String LIFO = "LIFO";
    public static final String FEFO_GRADE = "FEFO_GRADE";
    public static final String ZONE_PRIORITY = "ZONE_PRIORITY";
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record PickingRuleZonePriority(
      UUID id, UUID tenantId, UUID ruleId, UUID zoneId, int priority) {}

  public record PickingRuleAssignment(
      UUID id, UUID tenantId, UUID ruleId, String scopeType, UUID scopeId, Instant createdAt) {
    public static final String GLOBAL = "GLOBAL";
    public static final String STORE = "STORE";
    public static final String PRODUCT = "PRODUCT";
  }

  public record PhysicalInventory(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String status,
      String notes,
      Instant startedAt,
      Instant completedAt) {
    public static final String OPEN = "OPEN";
    public static final String COUNTING = "COUNTING";
    public static final String COMPLETED = "COMPLETED";
  }

  public record PhysicalInventoryTag(
      UUID id,
      UUID tenantId,
      UUID physicalInventoryId,
      UUID variantId,
      UUID zoneId,
      java.math.BigDecimal systemQty,
      java.math.BigDecimal countedQty,
      java.math.BigDecimal adjustmentQty,
      String status,
      Instant countedAt) {
    public static final String OPEN = "OPEN";
    public static final String COUNTED = "COUNTED";
    public static final String ADJUSTED = "ADJUSTED";
  }
}
