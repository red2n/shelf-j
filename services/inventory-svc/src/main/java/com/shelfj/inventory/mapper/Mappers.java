package com.shelfj.inventory.mapper;

import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.inventory.domain.Domain.AccountingPeriod;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CostingMethod;
import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.KanbanCard;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.LevelSummary;
import com.shelfj.inventory.domain.Domain.LotAction;
import com.shelfj.inventory.domain.Domain.LotGenealogyLink;
import com.shelfj.inventory.domain.Domain.LotUomConversion;
import com.shelfj.inventory.domain.Domain.LowStockRow;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.ParLevelConfig;
import com.shelfj.inventory.domain.Domain.PhysicalInventory;
import com.shelfj.inventory.domain.Domain.PhysicalInventoryTag;
import com.shelfj.inventory.domain.Domain.PickingRule;
import com.shelfj.inventory.domain.Domain.PickingRuleAssignment;
import com.shelfj.inventory.domain.Domain.PickingRuleZonePriority;
import com.shelfj.inventory.domain.Domain.ReasonCode;
import com.shelfj.inventory.domain.Domain.ReorderPointPlan;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.inventory.domain.Domain.SerialMovement;
import com.shelfj.inventory.domain.Domain.SerialNumber;
import com.shelfj.inventory.domain.Domain.ShrinkageRow;
import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.domain.Domain.TransactionSourceType;
import com.shelfj.inventory.domain.Domain.TransferOrder;
import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.inventory.domain.Domain.ValuationRow;
import com.shelfj.inventory.domain.Domain.ZoneGlMapping;
import com.shelfj.inventory.dto.Dtos.AbcAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.AbcCompileRunResponse;
import com.shelfj.inventory.dto.Dtos.AccountingPeriodResponse;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.CostingMethodResponse;
import com.shelfj.inventory.dto.Dtos.CycleCountHeaderResponse;
import com.shelfj.inventory.dto.Dtos.CycleCountLineResponse;
import com.shelfj.inventory.dto.Dtos.DemandBucketResponse;
import com.shelfj.inventory.dto.Dtos.ExpiringBatchResponse;
import com.shelfj.inventory.dto.Dtos.KanbanCardResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.LevelSummaryResponse;
import com.shelfj.inventory.dto.Dtos.LotActionResponse;
import com.shelfj.inventory.dto.Dtos.LotGenealogyLinkResponse;
import com.shelfj.inventory.dto.Dtos.LotUomConversionResponse;
import com.shelfj.inventory.dto.Dtos.LowStockRowResponse;
import com.shelfj.inventory.dto.Dtos.MoveOrderLineResponse;
import com.shelfj.inventory.dto.Dtos.MoveOrderResponse;
import com.shelfj.inventory.dto.Dtos.MovementResponse;
import com.shelfj.inventory.dto.Dtos.ParLevelResponse;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryResponse;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryTagResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleZonePriorityResponse;
import com.shelfj.inventory.dto.Dtos.ReasonCodeResponse;
import com.shelfj.inventory.dto.Dtos.ReservationResponse;
import com.shelfj.inventory.dto.Dtos.RopPlanResponse;
import com.shelfj.inventory.dto.Dtos.SafetyStockParamsResponse;
import com.shelfj.inventory.dto.Dtos.SerialMovementResponse;
import com.shelfj.inventory.dto.Dtos.SerialNumberResponse;
import com.shelfj.inventory.dto.Dtos.ShrinkageRowResponse;
import com.shelfj.inventory.dto.Dtos.SourceTypeResponse;
import com.shelfj.inventory.dto.Dtos.SuggestionResponse;
import com.shelfj.inventory.dto.Dtos.ThresholdResponse;
import com.shelfj.inventory.dto.Dtos.TransferOrderLineResponse;
import com.shelfj.inventory.dto.Dtos.TransferOrderResponse;
import com.shelfj.inventory.dto.Dtos.ValuationRowResponse;
import com.shelfj.inventory.dto.Dtos.ZoneGlMappingResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class Mappers {

  private Mappers() {}

  public static LevelSummaryResponse toLevelSummary(LevelSummary s) {
    return new LevelSummaryResponse(s.skuCount(), s.lowStockCount());
  }

  public static LevelResponse toLevel(Level l) {
    return new LevelResponse(
        l.storeId().toString(), l.variantId().toString(), l.onHand(), l.reserved(), l.available());
  }

  public static BatchResponse toBatch(Batch b) {
    return new BatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        b.receivedQty(),
        b.remainingQty(),
        b.costPrice(),
        b.expiryDate() == null ? null : b.expiryDate().toString(),
        ts(b.createdAt()),
        b.status(),
        b.materialStatus(),
        b.materialStatusReason(),
        b.grade(),
        b.zoneId() == null ? null : b.zoneId().toString());
  }

  public static ReservationResponse toReservation(Reservation r) {
    return new ReservationResponse(
        r.id().toString(),
        r.storeId().toString(),
        r.variantId().toString(),
        r.qty(),
        r.orderId() == null ? null : r.orderId().toString(),
        r.status(),
        r.expiresAt() == null ? null : r.expiresAt().toString(),
        ts(r.createdAt()));
  }

  public static LowStockRowResponse toLowStockRow(LowStockRow r) {
    return new LowStockRowResponse(
        r.storeId(), r.variantId(), r.signal(), r.reorderLevel(), r.availableQty(), r.shortfall());
  }

  public static ValuationRowResponse toValuationRow(ValuationRow r) {
    return new ValuationRowResponse(
        r.groupKey(), r.method(), r.onHandQty(), r.unvaluedQty(), r.value());
  }

  public static ShrinkageRowResponse toShrinkageRow(ShrinkageRow r) {
    return new ShrinkageRowResponse(
        r.groupKey(), r.qtyWrittenOff(), r.qtyFound(), r.netQty(), r.movements());
  }

  public static MovementResponse toMovement(Movement m) {
    return new MovementResponse(
        m.id().toString(),
        m.storeId().toString(),
        m.variantId().toString(),
        m.batchId() == null ? null : m.batchId().toString(),
        m.type(),
        m.qty(),
        m.refType(),
        m.refId() == null ? null : m.refId().toString(),
        m.reasonCode(),
        m.actorId() == null ? null : m.actorId().toString(),
        ts(m.createdAt()));
  }

  public static ThresholdResponse toThreshold(Threshold t) {
    return new ThresholdResponse(
        t.id().toString(),
        t.storeId().toString(),
        t.variantId().toString(),
        t.threshold(),
        t.maxQty());
  }

  public static SuggestionResponse toSuggestion(Suggestion s) {
    return new SuggestionResponse(
        s.id().toString(),
        s.storeId().toString(),
        s.variantId().toString(),
        s.availableQty(),
        s.minQty(),
        s.maxQty(),
        s.suggestedQty(),
        s.status(),
        ts(s.createdAt()),
        ts(s.resolvedAt()));
  }

  public static SerialNumberResponse toSerial(SerialNumber s) {
    return new SerialNumberResponse(
        s.id().toString(),
        s.storeId().toString(),
        s.variantId().toString(),
        s.batchId() == null ? null : s.batchId().toString(),
        s.serialNo(),
        s.status(),
        ts(s.receivedAt()),
        ts(s.soldAt()));
  }

  public static SerialMovementResponse toSerialMovement(SerialMovement m) {
    return new SerialMovementResponse(
        m.id().toString(),
        m.serialId().toString(),
        m.fromStatus(),
        m.toStatus(),
        m.refType(),
        m.refId() == null ? null : m.refId().toString(),
        ts(m.createdAt()));
  }

  public static DemandBucketResponse toDemandBucket(DemandBucket b) {
    return new DemandBucketResponse(
        b.storeId().toString(),
        b.variantId().toString(),
        b.bucketDate().toString(),
        b.bucketType(),
        b.demandQty(),
        b.movementCount(),
        ts(b.computedAt()));
  }

  public static TransferOrderLineResponse toTransferOrderLine(TransferOrderLine l) {
    return new TransferOrderLineResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.requestedQty(),
        l.shippedQty(),
        l.receivedQty());
  }

  public static TransferOrderResponse toTransferOrder(
      TransferOrder o, List<TransferOrderLine> lines) {
    return new TransferOrderResponse(
        o.id().toString(),
        o.fromStoreId().toString(),
        o.toStoreId().toString(),
        o.transferType(),
        o.status(),
        o.notes(),
        ts(o.createdAt()),
        ts(o.shippedAt()),
        ts(o.receivedAt()),
        lines.stream().map(Mappers::toTransferOrderLine).toList());
  }

  public static MoveOrderLineResponse toMoveOrderLine(MoveOrderLine l) {
    return new MoveOrderLineResponse(
        l.id().toString(), l.variantId().toString(), l.requestedQty(), l.pickedQty());
  }

  public static MoveOrderResponse toMoveOrder(MoveOrder o, List<MoveOrderLine> lines) {
    return new MoveOrderResponse(
        o.id().toString(),
        o.fromStoreId().toString(),
        o.toStoreId().toString(),
        o.fromZone(),
        o.toZone(),
        o.notes(),
        o.status(),
        ts(o.createdAt()),
        ts(o.pickedAt()),
        lines.stream().map(Mappers::toMoveOrderLine).toList());
  }

  public static LotGenealogyLinkResponse toLotLink(LotGenealogyLink l) {
    return new LotGenealogyLinkResponse(
        l.id().toString(),
        l.parentBatchId().toString(),
        l.childBatchId().toString(),
        l.qty(),
        l.relationType(),
        l.notes(),
        ts(l.createdAt()));
  }

  public static CycleCountLineResponse toCycleCountLine(CycleCountLine l) {
    return new CycleCountLineResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.systemQty(),
        l.countedQty(),
        l.variance(),
        l.variancePct(),
        l.status(),
        ts(l.countedAt()));
  }

  public static CycleCountHeaderResponse toCycleCountHeader(
      CycleCountHeader h, List<CycleCountLine> lines) {
    int counted = (int) lines.stream().filter(l -> !CycleCountLine.OPEN.equals(l.status())).count();
    int approved =
        (int)
            lines.stream()
                .filter(
                    l ->
                        CycleCountLine.APPROVED.equals(l.status())
                            || CycleCountLine.ADJUSTED.equals(l.status()))
                .count();
    return new CycleCountHeaderResponse(
        h.id().toString(),
        h.storeId().toString(),
        h.name(),
        h.abcClasses(),
        h.tolerancePct(),
        h.status(),
        lines.size(),
        counted,
        approved,
        ts(h.createdAt()),
        ts(h.completedAt()));
  }

  public static AbcCompileRunResponse toAbcCompileRun(AbcCompileRun r) {
    return new AbcCompileRunResponse(
        r.id().toString(),
        r.storeId() == null ? null : r.storeId().toString(),
        r.criteria(),
        r.thresholdA(),
        r.thresholdAB(),
        r.itemsCompiled(),
        ts(r.compiledAt()));
  }

  public static AbcAssignmentResponse toAbcAssignment(AbcAssignment a) {
    return new AbcAssignmentResponse(
        a.id().toString(),
        a.storeId().toString(),
        a.variantId().toString(),
        a.runId().toString(),
        a.abcClass(),
        a.score(),
        a.rank(),
        ts(a.assignedAt()));
  }

  public static SafetyStockParamsResponse toSafetyStockParams(SafetyStockParams p) {
    return new SafetyStockParamsResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.method(),
        p.leadTimeDays(),
        p.serviceLevelPct(),
        p.userDefinedPct(),
        p.safetyStockQty(),
        ts(p.computedAt()),
        ts(p.createdAt()));
  }

  public static PhysicalInventoryTagResponse toTag(PhysicalInventoryTag t) {
    return new PhysicalInventoryTagResponse(
        t.id().toString(),
        t.variantId().toString(),
        t.zoneId() == null ? null : t.zoneId().toString(),
        t.systemQty(),
        t.countedQty(),
        t.adjustmentQty(),
        t.status(),
        ts(t.countedAt()));
  }

  public static PhysicalInventoryResponse toPhysicalInventory(
      PhysicalInventory pi, List<PhysicalInventoryTag> tags) {
    return new PhysicalInventoryResponse(
        pi.id().toString(),
        pi.storeId().toString(),
        pi.status(),
        pi.notes(),
        ts(pi.startedAt()),
        ts(pi.completedAt()),
        tags.stream().map(Mappers::toTag).toList());
  }

  public static RopPlanResponse toRopPlan(ReorderPointPlan p) {
    return new RopPlanResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.leadTimeDays(),
        p.orderingCost(),
        p.holdingCostPct(),
        p.unitCost(),
        p.avgDailyDemand(),
        p.rop(),
        p.eoq(),
        p.minOrderQty(),
        p.maxOrderQty(),
        p.lotMultiplier(),
        ts(p.computedAt()),
        ts(p.createdAt()));
  }

  public static KanbanCardResponse toKanbanCard(KanbanCard k) {
    return new KanbanCardResponse(
        k.id().toString(),
        k.storeId().toString(),
        k.variantId().toString(),
        k.kanbanType(),
        k.status(),
        k.reorderQty(),
        k.sourceStoreId() == null ? null : k.sourceStoreId().toString(),
        k.supplierRef(),
        k.notes(),
        k.minOrderQty(),
        k.maxOrderQty(),
        k.lotMultiplier(),
        ts(k.createdAt()),
        ts(k.triggeredAt()),
        ts(k.replenishedAt()));
  }

  public static CostingMethodResponse toCostingMethod(CostingMethod cm) {
    return new CostingMethodResponse(
        cm.id().toString(),
        cm.storeId().toString(),
        cm.variantId().toString(),
        cm.method(),
        cm.averageCost(),
        cm.updatedAt().toString());
  }

  public static AccountingPeriodResponse toPeriod(AccountingPeriod ap) {
    return new AccountingPeriodResponse(
        ap.id().toString(),
        ap.storeId().toString(),
        ap.periodName(),
        ap.periodDate().toString(),
        ap.status(),
        ts(ap.openedAt()),
        ts(ap.closedAt()));
  }

  // ── Tier-1 mappers ────────────────────────────────────────────────────────

  public static ReasonCodeResponse toReasonCode(ReasonCode r) {
    return new ReasonCodeResponse(
        r.id().toString(),
        r.tenantId().toString(),
        r.code(),
        r.description(),
        r.active(),
        ts(r.createdAt()));
  }

  public static SourceTypeResponse toSourceType(TransactionSourceType t) {
    return new SourceTypeResponse(
        t.id().toString(),
        t.tenantId().toString(),
        t.code(),
        t.description(),
        t.active(),
        ts(t.createdAt()));
  }

  public static LotActionResponse toLotAction(LotAction a) {
    return new LotActionResponse(
        a.id().toString(),
        a.actionType(),
        a.sourceBatchId().toString(),
        a.resultBatchId().toString(),
        a.qty(),
        a.notes(),
        ts(a.createdAt()));
  }

  public static ExpiringBatchResponse toExpiringBatch(Batch b) {
    long daysUntil =
        b.expiryDate() == null
            ? Long.MAX_VALUE
            : ChronoUnit.DAYS.between(LocalDate.now(), b.expiryDate());
    return new ExpiringBatchResponse(
        b.id().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        b.remainingQty(),
        b.expiryDate() == null ? null : b.expiryDate().toString(),
        daysUntil);
  }

  public static LotUomConversionResponse toLotUomConversion(LotUomConversion c) {
    return new LotUomConversionResponse(
        c.id().toString(),
        c.batchId().toString(),
        c.fromUom(),
        c.toUom(),
        c.factor(),
        c.notes(),
        ts(c.createdAt()));
  }

  public static ParLevelResponse toParLevel(ParLevelConfig p) {
    return new ParLevelResponse(
        p.id().toString(),
        p.storeId().toString(),
        p.variantId().toString(),
        p.parQty(),
        p.uom(),
        p.reviewCycle(),
        ts(p.createdAt()),
        ts(p.updatedAt()));
  }

  public static ZoneGlMappingResponse toZoneGlMapping(ZoneGlMapping z) {
    return new ZoneGlMappingResponse(
        z.id().toString(),
        z.storeId().toString(),
        z.zoneId() == null ? null : z.zoneId().toString(),
        z.nominalCode(),
        z.description(),
        ts(z.createdAt()),
        ts(z.updatedAt()));
  }

  public static PickingRuleResponse toPickingRule(PickingRule r) {
    return new PickingRuleResponse(
        r.id().toString(),
        r.name(),
        r.strategy(),
        r.gradePreference(),
        r.status(),
        ts(r.createdAt()),
        ts(r.updatedAt()));
  }

  public static PickingRuleZonePriorityResponse toZonePriority(PickingRuleZonePriority p) {
    return new PickingRuleZonePriorityResponse(
        p.id().toString(), p.zoneId().toString(), p.priority());
  }

  public static PickingRuleAssignmentResponse toPickingRuleAssignment(PickingRuleAssignment a) {
    return new PickingRuleAssignmentResponse(
        a.id().toString(),
        a.ruleId().toString(),
        a.scopeType(),
        a.scopeId() == null ? null : a.scopeId().toString(),
        ts(a.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
