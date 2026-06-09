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
      String expiryDate) {}

  public record AdjustRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull BigDecimal delta,
      String reason) {}

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
      String materialStatusReason) {}

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
}
