package com.shelfj.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

  /**
   * Aggregate counts over levels: total distinct SKUs and how many are at/below the low threshold.
   */
  public record LevelSummary(long skuCount, long lowStockCount) {}

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

  /**
   * Who caused a stock movement, and why (SJ-D4).
   *
   * <p>{@link #system()} is the common case: a movement caused by an order, GRN or transfer already
   * carries {@code refType}/{@code refId} pointing at that record, which names its own actor, so
   * repeating it here would duplicate rather than add. Adjustments are the exception -- they are
   * written with no {@code refId}, so without this nothing links a stock correction to a person or
   * a reason, and shrinkage cannot be attributed.
   *
   * @param reasonCode a {@code transaction_reason_codes} code, or null
   * @param actorId the authenticated user who performed the adjustment, or null for a system flow
   */
  public record MovementAttribution(String reasonCode, UUID actorId) {

    /**
     * Reason code for a cycle-count variance write-off. Not seeded in {@code
     * transaction_reason_codes} because it is not operator-chosen -- the engine assigns it.
     */
    public static final String CYCLE_COUNT_VARIANCE = "CYCLE_COUNT_VARIANCE";

    private static final MovementAttribution SYSTEM = new MovementAttribution(null, null);

    /** A movement caused by a system flow, traceable through its {@code refType}/{@code refId}. */
    public static MovementAttribution system() {
      return SYSTEM;
    }

    /** A movement a person deliberately made: records who, and why. */
    public static MovementAttribution by(UUID actorId, String reasonCode) {
      return new MovementAttribution(reasonCode, actorId);
    }
  }

  /**
   * One line of the live low-stock report.
   *
   * @param signal which configured level bound this row — THRESHOLD, SAFETY_STOCK or REORDER_POINT
   * @param reorderLevel the binding level: the highest of whichever signals are configured
   * @param availableQty on hand minus held reservations, matching the levels list's definition
   * @param shortfall how far below the level the item is
   */
  public record LowStockRow(
      String storeId,
      String variantId,
      String signal,
      BigDecimal reorderLevel,
      BigDecimal availableQty,
      BigDecimal shortfall) {}

  /** How the valuation report groups its rows. An enum, so no request text reaches the SQL. */
  public enum ValuationGrouping {
    STORE,
    VARIANT
  }

  /**
   * One line of the inventory valuation report.
   *
   * @param groupKey the store id or variant id this line values
   * @param method the costing basis used — FIFO, AVERAGE, or MIXED for a store rollup spanning both
   * @param onHandQty total remaining quantity
   * @param unvaluedQty how much of {@code onHandQty} carries no cost and is therefore excluded from
   *     {@code value}; reported rather than valued at zero, which would understate the holding
   * @param value the money value of the quantity that could be costed
   */
  public record ValuationRow(
      String groupKey,
      String method,
      BigDecimal onHandQty,
      BigDecimal unvaluedQty,
      BigDecimal value) {}

  /** How a shrinkage report groups its rows. An enum, so no request text ever reaches the SQL. */
  public enum ShrinkageGrouping {
    REASON,
    ACTOR,
    STORE
  }

  /**
   * One aggregated line of the shrinkage report.
   *
   * <p>Losses and gains stay separate rather than collapsing into {@code netQty} alone: a store
   * that wrote off 100 units and found 100 more is not the same as a store that did nothing.
   *
   * @param groupKey the reason code, actor id, store id or variant id this line sums
   * @param qtyWrittenOff total quantity removed, as a positive number
   * @param qtyFound total quantity added back
   * @param netQty signed net of the two
   * @param movements how many adjustment movements the line covers
   */
  public record ShrinkageRow(
      String groupKey,
      BigDecimal qtyWrittenOff,
      BigDecimal qtyFound,
      BigDecimal netQty,
      long movements) {}

  /**
   * How the stock-turn and dead-stock reports group their rows. An enum, so no request text reaches
   * the SQL.
   */
  public enum StockTurnGrouping {
    STORE,
    VARIANT
  }

  /**
   * One line of the stock-turn report.
   *
   * <p>Cost of goods sold is taken from the movement ledger rather than from revenue: every SALE
   * movement names the batch it drew down, and a batch's {@code cost_price} never changes after
   * receipt, so the cost of a sale is exactly recoverable however long ago it happened.
   *
   * <p>Opening and closing values are reconstructed the same way — a batch's quantity at any
   * instant is the sum of its own movements before that instant — which makes the report correct
   * for a historical window, not only for one ending today.
   *
   * @param groupKey the store id or variant id this line covers
   * @param cogs cost of the stock sold during the window
   * @param uncostedSaleQty quantity sold out of batches carrying no cost price, and therefore
   *     excluded from {@code cogs}; reported rather than costed at zero, which would overstate
   *     margin and understate turns
   * @param openingValue value of the holding at the window's start
   * @param closingValue value of the holding at the window's end
   * @param averageValue mean of opening and closing — the denominator of {@code turnoverRatio}
   * @param turnoverRatio {@code cogs / averageValue}; null when there was no stock to turn, which
   *     is not the same as turning it zero times
   * @param daysOnHand how many days the average holding would last at this rate of sale; null
   *     whenever {@code turnoverRatio} is
   */
  public record StockTurnRow(
      String groupKey,
      BigDecimal cogs,
      BigDecimal uncostedSaleQty,
      BigDecimal openingValue,
      BigDecimal closingValue,
      BigDecimal averageValue,
      BigDecimal turnoverRatio,
      BigDecimal daysOnHand) {}

  /**
   * The stock-turn report plus the one fact that decides whether its opening figures can be
   * trusted.
   *
   * @param rows one line per store or variant, slowest-turning first
   * @param historyComplete false when the movement ledger has been purged past the window's start
   *     ({@code stock_movements_archive}), so the replay cannot see every movement that preceded it
   *     and opening value is understated. The rows are still returned — a partial answer that says
   *     so beats no answer — but the ratio should not be read as exact.
   * @param windowDays length of the requested window, the numerator of {@code daysOnHand}
   */
  public record StockTurnReport(List<StockTurnRow> rows, boolean historyComplete, int windowDays) {}

  /** How the dead-stock report groups its rows. An enum, so no request text reaches the SQL. */
  public enum DeadStockGrouping {
    BUCKET,
    STORE,
    VARIANT
  }

  /**
   * One line of the dead-stock ageing report.
   *
   * <p>Age is measured from the last <em>sale</em> of that item at that store, not from receipt:
   * stock that arrived two years ago and sold yesterday is not dead. Stock that has never sold ages
   * from the receipt of its oldest remaining batch, which is the only date it has.
   *
   * @param groupKey the ageing bucket, store id or variant id this line covers
   * @param onHandQty quantity still on hand
   * @param value value of that quantity at batch cost
   * @param uncostedQty how much of {@code onHandQty} carries no cost and is excluded from {@code
   *     value}
   * @param daysSinceLastSale days since the item last sold; for a group, the largest such age in it
   *     — the oldest thing in the bucket is what a manager acts on
   * @param neverSold true when nothing in this line has ever sold, so its age is measured from
   *     receipt instead
   */
  public record DeadStockRow(
      String groupKey,
      BigDecimal onHandQty,
      BigDecimal value,
      BigDecimal uncostedQty,
      Integer daysSinceLastSale,
      boolean neverSold) {}

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
      UUID actorId,
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
