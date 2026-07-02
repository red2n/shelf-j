package com.shelfj.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/** Request/response DTOs for inventory-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  public record ReceiveRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      String expiryDate,
      String grade,
      String zoneId) {}

  public record BatchReceiveItem(
      @NotBlank String storeId, @NotBlank String variantId, @NotNull @Positive BigDecimal qty) {}

  public record BatchReceiveRequest(@NotNull List<BatchReceiveItem> items) {}

  public record BatchReceiveResult(int received, List<String> errors) {}

  public record AdjustRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull BigDecimal delta,
      String reason,
      String reasonCode) {}

  public record ReserveRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      String orderId,
      Long ttlSeconds) {}

  public record ThresholdRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal threshold,
      BigDecimal maxQty) {}

  public record MaterialStatusRequest(@NotBlank String materialStatus, String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  public record LevelResponse(
      String storeId,
      String variantId,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  public record BatchResponse(
      String id,
      String storeId,
      String variantId,
      String batchNo,
      BigDecimal receivedQty,
      BigDecimal remainingQty,
      BigDecimal costPrice,
      String expiryDate,
      String createdAt,
      String status,
      String materialStatus,
      String materialStatusReason,
      String grade,
      String zoneId) {}

  public record ReservationResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal qty,
      String orderId,
      String status,
      String expiresAt,
      String createdAt) {}

  public record MovementResponse(
      String id,
      String storeId,
      String variantId,
      String batchId,
      String type,
      BigDecimal qty,
      String refType,
      String refId,
      String reasonCode,
      String createdAt) {}

  public record ThresholdResponse(
      String id, String storeId, String variantId, BigDecimal threshold, BigDecimal maxQty) {}

  public record SuggestionResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal availableQty,
      BigDecimal minQty,
      BigDecimal maxQty,
      BigDecimal suggestedQty,
      String status,
      String createdAt,
      String resolvedAt) {}

  public record ResolveSuggestionRequest(@NotBlank String status) {}

  public record RegisterSerialsRequest(
      @NotBlank String batchId,
      @NotBlank String storeId,
      @NotBlank String variantId,
      List<String> serials,
      Integer autoQty,
      String prefix) {}

  public record SerialStatusRequest(@NotBlank String status) {}

  public record SerialNumberResponse(
      String id,
      String storeId,
      String variantId,
      String batchId,
      String serialNo,
      String status,
      String receivedAt,
      String soldAt) {}

  public record SerialMovementResponse(
      String id,
      String serialId,
      String fromStatus,
      String toStatus,
      String refType,
      String refId,
      String createdAt) {}

  // ── Move Orders (Gap #5) ─────────────────────────────────────────────────────

  public record MoveOrderLineRequest(
      @NotBlank String variantId, @NotNull @Positive BigDecimal requestedQty) {}

  public record CreateMoveOrderRequest(
      @NotBlank String fromStoreId,
      @NotBlank String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      @NotNull List<MoveOrderLineRequest> lines) {}

  public record MoveOrderLineResponse(
      String id, String variantId, BigDecimal requestedQty, BigDecimal pickedQty) {}

  public record MoveOrderResponse(
      String id,
      String fromStoreId,
      String toStoreId,
      String fromZone,
      String toZone,
      String notes,
      String status,
      String createdAt,
      String pickedAt,
      List<MoveOrderLineResponse> lines) {}

  // ── Transfer Orders (Gap #6) ─────────────────────────────────────────────────

  public record TransferOrderLineRequest(
      @NotBlank String variantId, @NotNull @Positive BigDecimal requestedQty) {}

  public record CreateTransferOrderRequest(
      @NotBlank String fromStoreId,
      @NotBlank String toStoreId,
      String transferType,
      String notes,
      @NotNull List<TransferOrderLineRequest> lines) {}

  public record TransferOrderLineResponse(
      String id,
      String variantId,
      BigDecimal requestedQty,
      BigDecimal shippedQty,
      BigDecimal receivedQty) {}

  public record TransferOrderResponse(
      String id,
      String fromStoreId,
      String toStoreId,
      String transferType,
      String status,
      String notes,
      String createdAt,
      String shippedAt,
      String receivedAt,
      List<TransferOrderLineResponse> lines) {}

  // ── Lot Genealogy (Gap #11) ──────────────────────────────────────────────

  public record CreateLotLinkRequest(
      @NotBlank String parentBatchId,
      @NotBlank String childBatchId,
      @NotNull @Positive BigDecimal qty,
      String relationType,
      String notes) {}

  public record LotGenealogyLinkResponse(
      String id,
      String parentBatchId,
      String childBatchId,
      BigDecimal qty,
      String relationType,
      String notes,
      String createdAt) {}

  public record LotGenealogyTreeResponse(
      String batchId,
      List<LotGenealogyLinkResponse> ancestors,
      List<LotGenealogyLinkResponse> descendants) {}

  // ── Cycle Counting (Gap #10) ─────────────────────────────────────────────

  public record CreateCycleCountRequest(
      @NotBlank String storeId,
      @NotBlank String name,
      String abcClasses,
      BigDecimal tolerancePct) {}

  public record EnterCountRequest(@NotNull BigDecimal countedQty) {}

  public record CycleCountLineResponse(
      String id,
      String variantId,
      BigDecimal systemQty,
      BigDecimal countedQty,
      BigDecimal variance,
      BigDecimal variancePct,
      String status,
      String countedAt) {}

  public record CycleCountHeaderResponse(
      String id,
      String storeId,
      String name,
      String abcClasses,
      BigDecimal tolerancePct,
      String status,
      int totalLines,
      int countedLines,
      int approvedLines,
      String createdAt,
      String completedAt) {}

  public record CycleCountApproveResult(int autoApproved, int flagged) {}

  public record CycleCountAdjustResult(int adjusted) {}

  // ── ABC Analysis (Gap #9) ────────────────────────────────────────────────

  public record RunAbcRequest(
      String storeId, String criteria, BigDecimal thresholdA, BigDecimal thresholdAB) {}

  public record AbcCompileRunResponse(
      String id,
      String storeId,
      String criteria,
      BigDecimal thresholdA,
      BigDecimal thresholdAB,
      int itemsCompiled,
      String compiledAt) {}

  public record AbcAssignmentResponse(
      String id,
      String storeId,
      String variantId,
      String runId,
      String abcClass,
      BigDecimal score,
      int rank,
      String assignedAt) {}

  // ── Safety Stock (Gap #8) ────────────────────────────────────────────────

  public record SetSafetyStockRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotBlank String method,
      Integer leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct) {}

  public record ComputeSafetyStockRequest(String storeId, String variantId) {}

  public record SafetyStockParamsResponse(
      String id,
      String storeId,
      String variantId,
      String method,
      int leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct,
      BigDecimal safetyStockQty,
      String computedAt,
      String createdAt) {}

  public record ComputeSafetyStockResult(int computed, String bucketType) {}

  public record AggregateRequest(String storeId, String bucketType, String since) {}

  public record AggregateResult(int bucketsUpserted, String bucketType) {}

  public record DemandBucketResponse(
      String storeId,
      String variantId,
      String bucketDate,
      String bucketType,
      BigDecimal demandQty,
      int movementCount,
      String computedAt) {}

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────

  public record UpsertRopPlanRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive Integer leadTimeDays,
      @NotNull @Positive BigDecimal orderingCost,
      @NotNull @Positive BigDecimal holdingCostPct,
      @NotNull @Positive BigDecimal unitCost) {}

  public record RopPlanResponse(
      String id,
      String storeId,
      String variantId,
      int leadTimeDays,
      BigDecimal orderingCost,
      BigDecimal holdingCostPct,
      BigDecimal unitCost,
      BigDecimal avgDailyDemand,
      BigDecimal rop,
      BigDecimal eoq,
      BigDecimal minOrderQty,
      BigDecimal maxOrderQty,
      BigDecimal lotMultiplier,
      String computedAt,
      String createdAt) {}

  public record ComputeRopResult(int computed) {}

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────

  public record CreateKanbanCardRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotBlank String kanbanType,
      @NotNull @Positive BigDecimal reorderQty,
      String sourceStoreId,
      String supplierRef,
      String notes) {}

  public record TriggerKanbanRequest(String notes) {}

  public record KanbanCardResponse(
      String id,
      String storeId,
      String variantId,
      String kanbanType,
      String status,
      BigDecimal reorderQty,
      String sourceStoreId,
      String supplierRef,
      String notes,
      BigDecimal minOrderQty,
      BigDecimal maxOrderQty,
      BigDecimal lotMultiplier,
      String createdAt,
      String triggeredAt,
      String replenishedAt) {}

  // ── Gap #17: Costing ─────────────────────────────────────────────────────

  public record UpsertCostingMethodRequest(
      @NotBlank String storeId, @NotBlank String variantId, @NotBlank String method) {}

  public record CostingMethodResponse(
      String id,
      String storeId,
      String variantId,
      String method,
      BigDecimal averageCost,
      String updatedAt) {}

  public record OpenPeriodRequest(
      @NotBlank String storeId, @NotBlank String periodName, @NotBlank String periodDate) {}

  public record AccountingPeriodResponse(
      String id,
      String storeId,
      String periodName,
      String periodDate,
      String status,
      String openedAt,
      String closedAt) {}

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  public record CreateReasonCodeRequest(@NotBlank String code, String description) {}

  public record ReasonCodeResponse(
      String id,
      String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #22: Transaction source types ──────────────────────────────

  public record CreateSourceTypeRequest(@NotBlank String code, String description) {}

  public record SourceTypeResponse(
      String id,
      String tenantId,
      String code,
      String description,
      boolean active,
      String createdAt) {}

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  public record LotSplitRequest(
      @NotBlank String sourceBatchId,
      @NotNull @Positive BigDecimal qty,
      String batchNo,
      String notes) {}

  public record LotMergeRequest(
      @NotBlank String sourceBatchId,
      @NotBlank String targetBatchId,
      @NotNull @Positive BigDecimal qty,
      String notes) {}

  public record LotActionResponse(
      String id,
      String actionType,
      String sourceBatchId,
      String resultBatchId,
      BigDecimal qty,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  public record ExpiringBatchResponse(
      String id,
      String storeId,
      String variantId,
      String batchNo,
      BigDecimal remainingQty,
      String expiryDate,
      long daysUntilExpiry) {}

  // ── Tier-1 Gap #25: Grade control ─────────────────────────────────────────

  public record UpdateGradeRequest(@NotBlank String grade) {}

  // ── Tier-1 Gap #26: Lot UOM conversions ───────────────────────────────────

  public record UpsertLotUomConversionRequest(
      @NotBlank String batchId,
      @NotBlank String fromUom,
      @NotBlank String toUom,
      @NotNull @Positive BigDecimal factor,
      String notes) {}

  public record LotUomConversionResponse(
      String id,
      String batchId,
      String fromUom,
      String toUom,
      BigDecimal factor,
      String notes,
      String createdAt) {}

  // ── Tier-1 Gap #27: PAR levels ────────────────────────────────────────────

  public record UpsertParLevelRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal parQty,
      String uom,
      String reviewCycle) {}

  public record ParLevelResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal parQty,
      String uom,
      String reviewCycle,
      String createdAt,
      String updatedAt) {}

  // ── Tier-1 Gap #28: Order modifiers on ROP plans ──────────────────────────

  public record UpdateOrderModifiersRequest(
      BigDecimal minOrderQty, BigDecimal maxOrderQty, BigDecimal lotMultiplier) {}

  // ── Tier-1 Gap #29: Batch reservations ────────────────────────────────────

  public record BatchReserveRequest(@NotNull List<ReserveRequest> reservations) {}

  public record BatchReserveResponse(
      int succeeded, int failed, List<ReservationResponse> results) {}

  // ── Tier-1 Gap #30: Purge transaction history ─────────────────────────────

  public record PurgeMovementsRequest(@NotBlank String before) {}

  public record PurgeResult(int purged) {}

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  public record UpsertZoneGlMappingRequest(
      @NotBlank String storeId, String zoneId, @NotBlank String nominalCode, String description) {}

  public record ZoneGlMappingResponse(
      String id,
      String storeId,
      String zoneId,
      String nominalCode,
      String description,
      String createdAt,
      String updatedAt) {}

  // ── Gap #38: Picking Rules ────────────────────────────────────────────────

  public record CreatePickingRuleRequest(
      @NotBlank String name, @NotBlank String strategy, String gradePreference) {}

  public record PickingRuleResponse(
      String id,
      String name,
      String strategy,
      String gradePreference,
      String status,
      String createdAt,
      String updatedAt) {}

  public record SetZonePrioritiesRequest(@NotNull List<ZonePriorityEntry> zonePriorities) {
    public record ZonePriorityEntry(@NotBlank String zoneId, int priority) {}
  }

  public record PickingRuleZonePriorityResponse(String id, String zoneId, int priority) {}

  public record CreatePickingRuleAssignmentRequest(
      @NotBlank String ruleId, @NotBlank String scopeType, String scopeId) {}

  public record PickingRuleAssignmentResponse(
      String id, String ruleId, String scopeType, String scopeId, String createdAt) {}

  public record PickingRuleResolveResponse(
      String appliedRuleId,
      String appliedRuleName,
      String strategy,
      String gradePreference,
      List<PickBatchPreview> pickOrder) {
    public record PickBatchPreview(
        String batchId,
        String batchNo,
        String zoneId,
        java.math.BigDecimal remainingQty,
        String expiryDate,
        String grade,
        String createdAt) {}
  }

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  public record CreatePhysicalInventoryRequest(@NotBlank String storeId, String notes) {}

  public record AddTagRequest(
      @NotBlank String variantId, String zoneId, @NotNull BigDecimal systemQty) {}

  public record CountTagRequest(@NotNull BigDecimal countedQty) {}

  public record PhysicalInventoryTagResponse(
      String id,
      String variantId,
      String zoneId,
      BigDecimal systemQty,
      BigDecimal countedQty,
      BigDecimal adjustmentQty,
      String status,
      String countedAt) {}

  public record PhysicalInventoryResponse(
      String id,
      String storeId,
      String status,
      String notes,
      String startedAt,
      String completedAt,
      List<PhysicalInventoryTagResponse> tags) {}

  /** Public storefront stock signal: whether a variant is buyable at a store (no quantities). */
  public record AvailabilityResponse(String variantId, boolean inStock) {}
}
