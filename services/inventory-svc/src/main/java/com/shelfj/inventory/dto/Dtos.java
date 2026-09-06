package com.shelfj.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for inventory-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "ReceiveRequest", description = "Manual receipt of stock into a new batch.")
  public record ReceiveRequest(
      @Schema(description = "UUID of the store receiving stock.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being received.") @NotBlank
          String variantId,
      @Schema(description = "Quantity received.") @NotNull @Positive BigDecimal qty,
      String batchNo,
      @Schema(description = "Unit cost of this batch.") BigDecimal costPrice,
      @Schema(description = "ISO expiry date, if perishable.") String expiryDate,
      String grade,
      @Schema(description = "UUID of the zone the batch is placed in.") String zoneId) {}

  @Schema(name = "BatchReceiveItem", description = "One line of a bulk receive request.")
  public record BatchReceiveItem(
      @Schema(description = "UUID of the store receiving stock.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being received.") @NotBlank
          String variantId,
      @Schema(description = "Quantity received.") @NotNull @Positive BigDecimal qty) {}

  @Schema(name = "BatchReceiveRequest", description = "Bulk receive of multiple items in one call.")
  public record BatchReceiveRequest(@NotNull @Valid List<BatchReceiveItem> items) {}

  @Schema(
      name = "BatchReceiveResult",
      description = "Outcome of a bulk receive: count succeeded plus per-line error messages.")
  public record BatchReceiveResult(int received, List<String> errors) {}

  @Schema(name = "AdjustRequest", description = "Manual stock correction by a signed delta.")
  public record AdjustRequest(
      @Schema(description = "UUID of the store being adjusted.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant being adjusted.") @NotBlank
          String variantId,
      @Schema(description = "Signed adjustment quantity; positive adds, negative removes stock.")
          @NotNull
          BigDecimal delta,
      String reason,
      String reasonCode) {}

  @Schema(name = "ReserveRequest", description = "Hold stock for an order.")
  public record ReserveRequest(
      @Schema(description = "UUID of the store to reserve from.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant to reserve.") @NotBlank String variantId,
      @Schema(description = "Quantity to hold.") @NotNull @Positive BigDecimal qty,
      @Schema(description = "UUID of the order this reservation is for.") String orderId,
      @Schema(description = "Hold duration in seconds; defaults to the service's configured TTL.")
          Long ttlSeconds) {}

  @Schema(
      name = "ThresholdRequest",
      description = "Reorder threshold that triggers a low-stock replenishment suggestion.")
  public record ThresholdRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Available qty at or below which a suggestion is raised.")
          @NotNull
          @Positive
          BigDecimal threshold,
      @Schema(description = "Optional cap on suggested replenishment qty.") BigDecimal maxQty) {}

  @Schema(name = "MaterialStatusRequest", description = "Hold/release-style batch material status.")
  public record MaterialStatusRequest(
      @Schema(description = "e.g. AVAILABLE, QUARANTINE, HOLD, REJECTED.") @NotBlank
          String materialStatus,
      String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "LevelResponse", description = "On-hand/reserved/available stock for a variant.")
  public record LevelResponse(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Total physical quantity in stock.") BigDecimal onHand,
      @Schema(description = "Quantity currently held by open reservations.") BigDecimal reserved,
      @Schema(description = "onHand minus reserved; the sellable quantity.")
          BigDecimal available) {}

  @Schema(name = "LevelSummaryResponse", description = "Aggregate stock-level KPI counts.")
  public record LevelSummaryResponse(
      @Schema(description = "Distinct SKUs with any stock level recorded.") long skuCount,
      @Schema(description = "SKUs at or below their reorder threshold.") long lowStockCount) {}

  @Schema(name = "BatchResponse", description = "A received lot/batch of stock.")
  public record BatchResponse(
      String id,
      @Schema(description = "UUID of the store the batch is held at.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String batchNo,
      @Schema(description = "Quantity originally received.") BigDecimal receivedQty,
      @Schema(description = "Quantity still remaining in this batch.") BigDecimal remainingQty,
      @Schema(description = "Unit cost of this batch.") BigDecimal costPrice,
      @Schema(description = "ISO expiry date, if perishable.") String expiryDate,
      String createdAt,
      @Schema(description = "e.g. ACTIVE, DEPLETED, CANCELLED.") String status,
      @Schema(description = "e.g. AVAILABLE, QUARANTINE, HOLD, REJECTED.") String materialStatus,
      String materialStatusReason,
      String grade,
      @Schema(description = "UUID of the zone the batch is placed in.") String zoneId) {}

  @Schema(name = "ReservationResponse", description = "A hold placed against available stock.")
  public record ReservationResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Quantity held.") BigDecimal qty,
      @Schema(description = "UUID of the order this reservation is for.") String orderId,
      @Schema(description = "HELD, CONSUMED, or RELEASED.") String status,
      @Schema(description = "Instant after which the hold auto-expires.") String expiresAt,
      String createdAt) {}

  @Schema(
      name = "ShrinkageRowResponse",
      description = "One aggregated line of the stock write-off report.")
  public record ShrinkageRowResponse(
      @Schema(
              description =
                  "What this line sums: a reason code, an actor id, a store id or a variant id,"
                      + " depending on the grouping. UNSPECIFIED covers adjustments made with no"
                      + " reason code; SYSTEM covers those with no human actor.")
          String groupKey,
      @Schema(description = "Total quantity written off, as a positive number.")
          BigDecimal qtyWrittenOff,
      @Schema(description = "Total quantity added back, e.g. stock found during a count.")
          BigDecimal qtyFound,
      @Schema(description = "Signed net of write-offs and finds.") BigDecimal netQty,
      @Schema(description = "How many adjustment movements this line covers.") long movements) {}

  @Schema(name = "MovementResponse", description = "One append-only stock movement ledger entry.")
  public record MovementResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the batch this movement affected, if applicable.")
          String batchId,
      @Schema(description = "e.g. RECEIPT, SALE, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN.")
          String type,
      @Schema(description = "Signed movement quantity.") BigDecimal qty,
      String refType,
      String refId,
      @Schema(
              description =
                  "Reason code for the movement, e.g. THEFT or DAMAGED. Set on adjustments; null"
                      + " for system-caused movements, which cite refType/refId instead.")
          String reasonCode,
      @Schema(
              description =
                  "UUID of the user who made this adjustment. Null for system-caused movements --"
                      + " trace those through refType/refId to the record that names its actor.")
          String actorId,
      String createdAt) {}

  @Schema(name = "ThresholdResponse", description = "A configured reorder threshold.")
  public record ThresholdResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Available qty at or below which a suggestion is raised.")
          BigDecimal threshold,
      @Schema(description = "Optional cap on suggested replenishment qty.") BigDecimal maxQty) {}

  @Schema(name = "SuggestionResponse", description = "A min-max replenishment suggestion.")
  public record SuggestionResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Available quantity at the time the suggestion was raised.")
          BigDecimal availableQty,
      BigDecimal minQty,
      BigDecimal maxQty,
      @Schema(description = "Recommended quantity to reorder.") BigDecimal suggestedQty,
      @Schema(description = "OPEN, ACCEPTED, or DISMISSED.") String status,
      String createdAt,
      String resolvedAt) {}

  @Schema(
      name = "ResolveSuggestionRequest",
      description = "Set a replenishment suggestion's status.")
  public record ResolveSuggestionRequest(
      @Schema(description = "ACCEPTED or DISMISSED.") @NotBlank String status) {}

  @Schema(name = "RegisterSerialsRequest", description = "Register serial numbers for a batch.")
  public record RegisterSerialsRequest(
      @Schema(description = "UUID of the batch the serials belong to.") @NotBlank String batchId,
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Explicit serial codes to register.") List<String> serials,
      @Schema(description = "Number of serials to auto-generate (max 200) if serials is omitted.")
          Integer autoQty,
      @Schema(description = "Prefix used when auto-generating serial codes.") String prefix) {}

  @Schema(name = "SerialStatusRequest", description = "Update a serial number's status.")
  public record SerialStatusRequest(
      @Schema(description = "e.g. IN_STOCK, SOLD, RETURNED, DAMAGED.") @NotBlank String status) {}

  @Schema(name = "SerialNumberResponse", description = "A tracked serialized unit.")
  public record SerialNumberResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the batch this serial was received in.") String batchId,
      String serialNo,
      @Schema(description = "e.g. IN_STOCK, SOLD, RETURNED, DAMAGED.") String status,
      String receivedAt,
      String soldAt) {}

  @Schema(
      name = "SerialMovementResponse",
      description = "A status transition for one serial number.")
  public record SerialMovementResponse(
      String id,
      @Schema(description = "UUID of the serial number.") String serialId,
      String fromStatus,
      String toStatus,
      String refType,
      String refId,
      String createdAt) {}

  // ── Move Orders (Gap #5) ─────────────────────────────────────────────────────

  @Schema(name = "MoveOrderLineRequest", description = "One requested variant/qty on a move order.")
  public record MoveOrderLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity requested to move.") @NotNull @Positive
          BigDecimal requestedQty) {}

  @Schema(
      name = "CreateMoveOrderRequest",
      description = "Intra-store zone-to-zone stock move request.")
  public record CreateMoveOrderRequest(
      @Schema(description = "UUID of the store both zones belong to.") @NotBlank String fromStoreId,
      @Schema(
              description =
                  "UUID of the destination store (same as fromStoreId for intra-store" + " moves).")
          @NotBlank
          String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      @NotNull @Valid List<MoveOrderLineRequest> lines) {}

  @Schema(name = "MoveOrderLineResponse", description = "One line of a move order.")
  public record MoveOrderLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal requestedQty,
      @Schema(description = "Quantity actually picked when the order was executed.")
          BigDecimal pickedQty) {}

  @Schema(
      name = "MoveOrderResponse",
      description = "A zone-to-zone stock move order with its lines.")
  public record MoveOrderResponse(
      String id,
      @Schema(description = "UUID of the source store.") String fromStoreId,
      @Schema(description = "UUID of the destination store.") String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      @Schema(description = "PENDING, PICKED, or CANCELLED.") String status,
      String createdAt,
      String pickedAt,
      List<MoveOrderLineResponse> lines) {}

  // ── Transfer Orders (Gap #6) ─────────────────────────────────────────────────

  @Schema(
      name = "TransferOrderLineRequest",
      description = "One requested variant/qty on a transfer order.")
  public record TransferOrderLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity requested to transfer.") @NotNull @Positive
          BigDecimal requestedQty) {}

  @Schema(name = "CreateTransferOrderRequest", description = "Inter-store stock transfer request.")
  public record CreateTransferOrderRequest(
      @Schema(description = "UUID of the sending store.") @NotBlank String fromStoreId,
      @Schema(description = "UUID of the receiving store.") @NotBlank String toStoreId,
      String transferType,
      String notes,
      @NotNull @Valid List<TransferOrderLineRequest> lines) {}

  @Schema(name = "TransferOrderLineResponse", description = "One line of a transfer order.")
  public record TransferOrderLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal requestedQty,
      @Schema(description = "Quantity actually shipped.") BigDecimal shippedQty,
      @Schema(description = "Quantity actually received at the destination store.")
          BigDecimal receivedQty) {}

  @Schema(
      name = "TransferOrderResponse",
      description = "An inter-store stock transfer order with its lines.")
  public record TransferOrderResponse(
      String id,
      @Schema(description = "UUID of the sending store.") String fromStoreId,
      @Schema(description = "UUID of the receiving store.") String toStoreId,
      String transferType,
      @Schema(description = "PENDING, SHIPPED, RECEIVED, or CANCELLED.") String status,
      String notes,
      String createdAt,
      String shippedAt,
      String receivedAt,
      List<TransferOrderLineResponse> lines) {}

  // ── Lot Genealogy (Gap #11) ──────────────────────────────────────────────

  @Schema(
      name = "CreateLotLinkRequest",
      description = "Link a parent and child batch for genealogy traceability.")
  public record CreateLotLinkRequest(
      @Schema(description = "UUID of the parent (source) batch.") @NotBlank String parentBatchId,
      @Schema(description = "UUID of the child (resulting) batch.") @NotBlank String childBatchId,
      @Schema(description = "Quantity attributed to this link.") @NotNull @Positive BigDecimal qty,
      @Schema(description = "e.g. SPLIT, MERGE, REPACK.") String relationType,
      String notes) {}

  @Schema(name = "LotGenealogyLinkResponse", description = "One parent-child genealogy link.")
  public record LotGenealogyLinkResponse(
      String id,
      @Schema(description = "UUID of the parent batch.") String parentBatchId,
      @Schema(description = "UUID of the child batch.") String childBatchId,
      BigDecimal qty,
      String relationType,
      String notes,
      String createdAt) {}

  @Schema(
      name = "LotGenealogyTreeResponse",
      description = "A batch's ancestor and/or descendant genealogy links.")
  public record LotGenealogyTreeResponse(
      @Schema(description = "UUID of the batch this tree is rooted at.") String batchId,
      List<LotGenealogyLinkResponse> ancestors,
      List<LotGenealogyLinkResponse> descendants) {}

  // ── Cycle Counting (Gap #10) ─────────────────────────────────────────────

  @Schema(name = "CreateCycleCountRequest", description = "Start a cycle count for a store.")
  public record CreateCycleCountRequest(
      @Schema(description = "UUID of the store being counted.") @NotBlank String storeId,
      @NotBlank String name,
      @Schema(description = "Comma-separated ABC classes included, e.g. \"A,B,C\".")
          String abcClasses,
      @Schema(description = "Variance percentage within which a line auto-approves.")
          BigDecimal tolerancePct) {}

  @Schema(name = "EnterCountRequest", description = "Record a counted quantity for a count line.")
  public record EnterCountRequest(
      @Schema(description = "Physically counted quantity.") @NotNull BigDecimal countedQty) {}

  @Schema(name = "CycleCountLineResponse", description = "One variant's line within a cycle count.")
  public record CycleCountLineResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Quantity per system records at count time.") BigDecimal systemQty,
      @Schema(description = "Physically counted quantity.") BigDecimal countedQty,
      @Schema(description = "countedQty minus systemQty.") BigDecimal variance,
      BigDecimal variancePct,
      @Schema(description = "PENDING, COUNTED, APPROVED, or FLAGGED.") String status,
      String countedAt) {}

  @Schema(name = "CycleCountHeaderResponse", description = "A cycle count with summary counts.")
  public record CycleCountHeaderResponse(
      String id,
      @Schema(description = "UUID of the store being counted.") String storeId,
      String name,
      String abcClasses,
      BigDecimal tolerancePct,
      @Schema(description = "OPEN, COUNTING, APPROVED, ADJUSTED, or COMPLETED.") String status,
      int totalLines,
      int countedLines,
      int approvedLines,
      String createdAt,
      String completedAt) {}

  @Schema(
      name = "CycleCountApproveResult",
      description = "Outcome of tolerance-based cycle-count approval.")
  public record CycleCountApproveResult(
      @Schema(description = "Lines auto-approved because variance was within tolerance.")
          int autoApproved,
      @Schema(description = "Lines flagged for manual review.") int flagged) {}

  @Schema(
      name = "CycleCountAdjustResult",
      description = "Outcome of posting cycle-count adjustments.")
  public record CycleCountAdjustResult(
      @Schema(description = "Number of StockAdjusted movements posted.") int adjusted) {}

  // ── ABC Analysis (Gap #9) ────────────────────────────────────────────────

  @Schema(name = "RunAbcRequest", description = "Parameters for an ABC classification compile run.")
  public record RunAbcRequest(
      @Schema(description = "UUID of the store to scope the run to; null for all stores.")
          String storeId,
      @Schema(description = "VALUE or VELOCITY.") String criteria,
      @Schema(description = "Cumulative percentage cutoff for class A (0-100).")
          BigDecimal thresholdA,
      @Schema(description = "Cumulative percentage cutoff for class B (thresholdA-100).")
          BigDecimal thresholdAB) {}

  @Schema(name = "AbcCompileRunResponse", description = "A completed ABC classification run.")
  public record AbcCompileRunResponse(
      String id,
      String storeId,
      String criteria,
      BigDecimal thresholdA,
      BigDecimal thresholdAB,
      @Schema(description = "Number of variants classified in this run.") int itemsCompiled,
      String compiledAt) {}

  @Schema(name = "AbcAssignmentResponse", description = "A variant's ABC classification.")
  public record AbcAssignmentResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the compile run this assignment came from.") String runId,
      @Schema(description = "A, B, or C.") String abcClass,
      @Schema(description = "Value or velocity score used to rank the variant.") BigDecimal score,
      @Schema(description = "Rank position within the run, 1 = highest score.") int rank,
      String assignedAt) {}

  // ── Safety Stock (Gap #8) ────────────────────────────────────────────────

  @Schema(name = "SetSafetyStockRequest", description = "Configure safety-stock parameters.")
  public record SetSafetyStockRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "MAD or USER_DEFINED.") @NotBlank String method,
      Integer leadTimeDays,
      @Schema(description = "Target service level percentage, e.g. 95.") BigDecimal serviceLevelPct,
      @Schema(
              description =
                  "Required when method is USER_DEFINED; a manual safety-stock" + " percentage.")
          BigDecimal userDefinedPct) {}

  @Schema(
      name = "ComputeSafetyStockRequest",
      description = "Scope for a safety-stock recompute; both fields optional.")
  public record ComputeSafetyStockRequest(String storeId, String variantId) {}

  @Schema(name = "SafetyStockParamsResponse", description = "Computed safety-stock parameters.")
  public record SafetyStockParamsResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "MAD or USER_DEFINED.") String method,
      int leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct,
      @Schema(description = "Computed safety-stock quantity to hold as a buffer.")
          BigDecimal safetyStockQty,
      String computedAt,
      String createdAt) {}

  @Schema(name = "ComputeSafetyStockResult", description = "Outcome of a safety-stock recompute.")
  public record ComputeSafetyStockResult(
      @Schema(description = "Number of safety-stock rows updated.") int computed,
      String bucketType) {}

  @Schema(
      name = "AggregateRequest",
      description = "Scope and grain for a demand-history aggregation run.")
  public record AggregateRequest(
      String storeId,
      @Schema(description = "DAY, WEEK, or MONTH.") String bucketType,
      @Schema(description = "ISO date; only movements on/after this date are aggregated.")
          String since) {}

  @Schema(name = "AggregateResult", description = "Outcome of a demand-history aggregation run.")
  public record AggregateResult(
      @Schema(description = "Number of demand buckets upserted.") int bucketsUpserted,
      String bucketType) {}

  @Schema(name = "DemandBucketResponse", description = "Aggregated demand for one time bucket.")
  public record DemandBucketResponse(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String bucketDate,
      @Schema(description = "DAY, WEEK, or MONTH.") String bucketType,
      @Schema(description = "Total SALE-movement quantity in this bucket.") BigDecimal demandQty,
      int movementCount,
      String computedAt) {}

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────

  @Schema(
      name = "UpsertRopPlanRequest",
      description = "Reorder-point/EOQ plan inputs for a variant at a store.")
  public record UpsertRopPlanRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Supplier lead time in days.") @NotNull @Positive Integer leadTimeDays,
      @Schema(description = "Fixed cost per purchase order, used in the EOQ formula.")
          @NotNull
          @Positive
          BigDecimal orderingCost,
      @Schema(description = "Annual holding cost as a percentage of unit cost.") @NotNull @Positive
          BigDecimal holdingCostPct,
      @Schema(description = "Unit cost used in the EOQ formula.") @NotNull @Positive
          BigDecimal unitCost) {}

  @Schema(name = "RopPlanResponse", description = "A computed reorder-point/EOQ plan.")
  public record RopPlanResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      int leadTimeDays,
      BigDecimal orderingCost,
      BigDecimal holdingCostPct,
      BigDecimal unitCost,
      @Schema(description = "Average daily demand derived from demand history.")
          BigDecimal avgDailyDemand,
      @Schema(description = "Computed reorder point.") BigDecimal rop,
      @Schema(description = "Computed economic order quantity.") BigDecimal eoq,
      @Schema(description = "Order-modifier floor applied to the computed EOQ.")
          BigDecimal minOrderQty,
      @Schema(description = "Order-modifier ceiling applied to the computed EOQ.")
          BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier,
      String computedAt,
      String createdAt) {}

  @Schema(name = "ComputeRopResult", description = "Outcome of a ROP/EOQ recompute.")
  public record ComputeRopResult(
      @Schema(description = "Number of plans recomputed.") int computed) {}

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────

  @Schema(name = "CreateKanbanCardRequest", description = "Create a kanban replenishment card.")
  public record CreateKanbanCardRequest(
      @Schema(description = "UUID of the store the card replenishes.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "e.g. PRODUCTION or TRANSFER.") @NotBlank String kanbanType,
      @Schema(description = "Fixed quantity ordered each time the card triggers.")
          @NotNull
          @Positive
          BigDecimal reorderQty,
      @Schema(description = "UUID of the store this card is replenished from, for TRANSFER cards.")
          String sourceStoreId,
      String supplierRef,
      String notes) {}

  @Schema(
      name = "TriggerKanbanRequest",
      description = "Optional notes when triggering a kanban card.")
  public record TriggerKanbanRequest(String notes) {}

  @Schema(name = "KanbanCardResponse", description = "A kanban replenishment card.")
  public record KanbanCardResponse(
      String id,
      @Schema(description = "UUID of the store the card replenishes.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String kanbanType,
      @Schema(description = "READY, TRIGGERED, or REPLENISHED.") String status,
      BigDecimal reorderQty,
      @Schema(description = "UUID of the source store for TRANSFER cards.") String sourceStoreId,
      String supplierRef,
      String notes,
      @Schema(description = "Order-modifier floor applied to reorderQty.") BigDecimal minOrderQty,
      @Schema(description = "Order-modifier ceiling applied to reorderQty.") BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier,
      String createdAt,
      String triggeredAt,
      String replenishedAt) {}

  // ── Gap #17: Costing ─────────────────────────────────────────────────────

  @Schema(
      name = "UpsertCostingMethodRequest",
      description = "Set the costing method for a variant at a store.")
  public record UpsertCostingMethodRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "FIFO or AVERAGE.") @NotBlank String method) {}

  @Schema(name = "CostingMethodResponse", description = "A variant's assigned costing method.")
  public record CostingMethodResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "FIFO or AVERAGE.") String method,
      @Schema(description = "Running average unit cost, maintained when method is AVERAGE.")
          BigDecimal averageCost,
      String updatedAt) {}

  @Schema(name = "OpenPeriodRequest", description = "Open a new accounting period for a store.")
  public record OpenPeriodRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @NotBlank String periodName,
      @Schema(description = "ISO date representing the period.") @NotBlank String periodDate) {}

  @Schema(name = "AccountingPeriodResponse", description = "An accounting period.")
  public record AccountingPeriodResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      String periodName,
      String periodDate,
      @Schema(description = "OPEN or CLOSED.") String status,
      String openedAt,
      String closedAt) {}

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  @Schema(name = "CreateReasonCodeRequest", description = "Create a transaction reason code.")
  public record CreateReasonCodeRequest(@NotBlank String code, String description) {}

  @Schema(name = "ReasonCodeResponse", description = "A transaction reason code.")
  public record ReasonCodeResponse(
      String id,
      @Schema(description = "UUID of the owning tenant.") String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #22: Transaction source types ──────────────────────────────

  @Schema(name = "CreateSourceTypeRequest", description = "Create a transaction source type.")
  public record CreateSourceTypeRequest(@NotBlank String code, String description) {}

  @Schema(name = "SourceTypeResponse", description = "A transaction source type.")
  public record SourceTypeResponse(
      String id,
      @Schema(description = "UUID of the owning tenant.") String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  @Schema(name = "LotSplitRequest", description = "Split a batch into a new child batch.")
  public record LotSplitRequest(
      @Schema(description = "UUID of the batch to split.") @NotBlank String sourceBatchId,
      @Schema(description = "Quantity to move into the new batch.") @NotNull @Positive
          BigDecimal qty,
      String batchNo,
      String notes) {}

  @Schema(
      name = "LotMergeRequest",
      description = "Merge qty from a source batch into a target batch.")
  public record LotMergeRequest(
      @Schema(description = "UUID of the batch to merge from.") @NotBlank String sourceBatchId,
      @Schema(description = "UUID of the batch to merge into.") @NotBlank String targetBatchId,
      @Schema(description = "Quantity to move.") @NotNull @Positive BigDecimal qty,
      String notes) {}

  @Schema(name = "LotActionResponse", description = "A recorded split or merge action.")
  public record LotActionResponse(
      String id,
      @Schema(description = "SPLIT or MERGE.") String actionType,
      @Schema(description = "UUID of the batch the qty was taken from.") String sourceBatchId,
      @Schema(description = "UUID of the batch the qty resulted in or was merged into.")
          String resultBatchId,
      BigDecimal qty,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  @Schema(name = "ExpiringBatchResponse", description = "A batch nearing its expiry date.")
  public record ExpiringBatchResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      String batchNo,
      BigDecimal remainingQty,
      String expiryDate,
      @Schema(description = "Days remaining until expiryDate, as of the query time.")
          long daysUntilExpiry) {}

  // ── Tier-1 Gap #25: Grade control ─────────────────────────────────────────

  @Schema(name = "UpdateGradeRequest", description = "Update a batch's quality grade.")
  public record UpdateGradeRequest(@NotBlank String grade) {}

  // ── Tier-1 Gap #26: Lot UOM conversions ───────────────────────────────────

  @Schema(
      name = "UpsertLotUomConversionRequest",
      description = "Define a unit-of-measure conversion for a specific batch.")
  public record UpsertLotUomConversionRequest(
      @Schema(description = "UUID of the batch this conversion applies to.") @NotBlank
          String batchId,
      @NotBlank String fromUom,
      @NotBlank String toUom,
      @Schema(description = "Multiplier: 1 fromUom = factor toUom.") @NotNull @Positive
          BigDecimal factor,
      String notes) {}

  @Schema(name = "LotUomConversionResponse", description = "A batch's unit-of-measure conversion.")
  public record LotUomConversionResponse(
      String id,
      @Schema(description = "UUID of the batch.") String batchId,
      String fromUom,
      String toUom,
      BigDecimal factor,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #27: PAR levels ────────────────────────────────────────────

  @Schema(
      name = "UpsertParLevelRequest",
      description = "Set a periodic-automatic-replenishment (PAR) target quantity.")
  public record UpsertParLevelRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Target quantity to be topped up to on each review cycle.")
          @NotNull
          @Positive
          BigDecimal parQty,
      String uom,
      @Schema(description = "e.g. DAILY, WEEKLY.") String reviewCycle) {}

  @Schema(name = "ParLevelResponse", description = "A configured PAR level.")
  public record ParLevelResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal parQty,
      String uom,
      String reviewCycle,
      String createdAt,
      String updatedAt) {}

  // ── Tier-1 Gap #28: Order modifiers on ROP plans ──────────────────────────

  @Schema(
      name = "UpdateOrderModifiersRequest",
      description = "Order-quantity modifiers applied on top of a computed reorder quantity.")
  public record UpdateOrderModifiersRequest(
      @Schema(description = "Floor applied to the computed order quantity.") BigDecimal minOrderQty,
      @Schema(description = "Ceiling applied to the computed order quantity.")
          BigDecimal maxOrderQty,
      @Schema(description = "Rounds the order qty up to a multiple of this lot size.")
          BigDecimal lotMultiplier) {}

  // ── Tier-1 Gap #29: Batch reservations ────────────────────────────────────

  @Schema(
      name = "BatchReserveRequest",
      description = "Reserve stock for multiple lines in one call.")
  public record BatchReserveRequest(@NotNull @Valid List<ReserveRequest> reservations) {}

  @Schema(name = "BatchReserveResponse", description = "Outcome of a bulk reservation request.")
  public record BatchReserveResponse(
      int succeeded, int failed, List<ReservationResponse> results) {}

  // ── Tier-1 Gap #30: Purge transaction history ─────────────────────────────

  @Schema(
      name = "PurgeMovementsRequest",
      description = "Delete movement history older than a given instant (data retention).")
  public record PurgeMovementsRequest(
      @Schema(description = "ISO-8601 instant; movements before this are permanently deleted.")
          @NotBlank
          String before) {}

  @Schema(name = "PurgeResult", description = "Outcome of a movement purge.")
  public record PurgeResult(@Schema(description = "Number of movements deleted.") int purged) {}

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  @Schema(
      name = "UpsertZoneGlMappingRequest",
      description = "Map a store/zone to a nominal ledger (GL) account.")
  public record UpsertZoneGlMappingRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "UUID of the zone; null maps the whole store.") String zoneId,
      @Schema(description = "Nominal ledger account code.") @NotBlank String nominalCode,
      String description) {}

  @Schema(name = "ZoneGlMappingResponse", description = "A zone-to-GL-account mapping.")
  public record ZoneGlMappingResponse(
      String id,
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the zone; null if mapped at store level.") String zoneId,
      String nominalCode,
      String description,
      String createdAt,
      String updatedAt) {}

  // ── Gap #38: Picking Rules ────────────────────────────────────────────────

  @Schema(name = "CreatePickingRuleRequest", description = "Create a named picking strategy rule.")
  public record CreatePickingRuleRequest(
      @NotBlank String name,
      @Schema(description = "e.g. FIFO, FEFO.") @NotBlank String strategy,
      String gradePreference) {}

  @Schema(name = "PickingRuleResponse", description = "A picking strategy rule.")
  public record PickingRuleResponse(
      String id,
      String name,
      String strategy,
      String gradePreference,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String createdAt,
      String updatedAt) {}

  @Schema(
      name = "SetZonePrioritiesRequest",
      description = "Ordered zone pick priorities for a picking rule.")
  public record SetZonePrioritiesRequest(@NotNull @Valid List<ZonePriorityEntry> zonePriorities) {
    @Schema(
        name = "ZonePriorityEntry",
        description = "One zone's pick priority (lower picks first).")
    public record ZonePriorityEntry(
        @Schema(description = "UUID of the zone.") @NotBlank String zoneId, int priority) {}
  }

  @Schema(
      name = "PickingRuleZonePriorityResponse",
      description = "A zone's priority within a rule.")
  public record PickingRuleZonePriorityResponse(
      String id, @Schema(description = "UUID of the zone.") String zoneId, int priority) {}

  @Schema(
      name = "CreatePickingRuleAssignmentRequest",
      description = "Bind a picking rule to a scope (e.g. tenant/store/category).")
  public record CreatePickingRuleAssignmentRequest(
      @Schema(description = "UUID of the picking rule.") @NotBlank String ruleId,
      @Schema(description = "e.g. TENANT, STORE, CATEGORY.") @NotBlank String scopeType,
      @Schema(description = "UUID of the scoped entity; null for a tenant-wide assignment.")
          String scopeId) {}

  @Schema(name = "PickingRuleAssignmentResponse", description = "A picking rule scope assignment.")
  public record PickingRuleAssignmentResponse(
      String id,
      @Schema(description = "UUID of the picking rule.") String ruleId,
      String scopeType,
      String scopeId,
      String createdAt) {}

  @Schema(
      name = "PickingRuleResolveResponse",
      description =
          "The applicable picking rule and previewed pick order for a variant at a store.")
  public record PickingRuleResolveResponse(
      @Schema(description = "UUID of the resolved picking rule.") String appliedRuleId,
      String appliedRuleName,
      String strategy,
      String gradePreference,
      List<PickBatchPreview> pickOrder) {
    @Schema(
        name = "PickBatchPreview",
        description = "One batch's position in the previewed pick order.")
    public record PickBatchPreview(
        @Schema(description = "UUID of the batch.") String batchId,
        String batchNo,
        @Schema(description = "UUID of the zone the batch is in.") String zoneId,
        java.math.BigDecimal remainingQty,
        String expiryDate,
        String grade,
        String createdAt) {}
  }

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  @Schema(
      name = "CreatePhysicalInventoryRequest",
      description = "Start a full physical inventory count for a store.")
  public record CreatePhysicalInventoryRequest(
      @Schema(description = "UUID of the store being counted.") @NotBlank String storeId,
      String notes) {}

  @Schema(name = "AddTagRequest", description = "Register a variant to be counted.")
  public record AddTagRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the zone the variant is expected in.") String zoneId,
      @Schema(description = "Quantity per system records at the time the tag is added.") @NotNull
          BigDecimal systemQty) {}

  @Schema(name = "CountTagRequest", description = "Record a counted quantity for a tag.")
  public record CountTagRequest(
      @Schema(description = "Physically counted quantity.") @NotNull BigDecimal countedQty) {}

  @Schema(
      name = "PhysicalInventoryTagResponse",
      description = "One variant/zone tag being counted.")
  public record PhysicalInventoryTagResponse(
      String id,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "UUID of the zone.") String zoneId,
      BigDecimal systemQty,
      BigDecimal countedQty,
      @Schema(description = "countedQty minus systemQty.") BigDecimal adjustmentQty,
      @Schema(description = "PENDING or COUNTED.") String status,
      String countedAt) {}

  @Schema(
      name = "PhysicalInventoryResponse",
      description = "A full physical inventory count with its tags.")
  public record PhysicalInventoryResponse(
      String id,
      @Schema(description = "UUID of the store being counted.") String storeId,
      @Schema(description = "IN_PROGRESS or COMPLETED.") String status,
      String notes,
      String startedAt,
      String completedAt,
      List<PhysicalInventoryTagResponse> tags) {}

  /** Public storefront stock signal: whether a variant is buyable at a store (no quantities). */
  @Schema(
      name = "AvailabilityResponse",
      description = "Public in-stock/out-of-stock signal for a variant; no quantities exposed.")
  public record AvailabilityResponse(
      @Schema(description = "UUID of the product variant.") String variantId, boolean inStock) {}
}
