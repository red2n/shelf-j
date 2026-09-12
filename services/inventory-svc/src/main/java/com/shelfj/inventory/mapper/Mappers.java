package com.shelfj.inventory.mapper;

import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.inventory.domain.Domain.AccountingPeriod;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CostingMethod;
import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.DeadStockRow;
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
import com.shelfj.inventory.domain.Domain.StockTurnReport;
import com.shelfj.inventory.domain.Domain.StockTurnRow;
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
import com.shelfj.inventory.dto.Dtos.DeadStockRowResponse;
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
import com.shelfj.inventory.dto.Dtos.StockTurnReportResponse;
import com.shelfj.inventory.dto.Dtos.StockTurnRowResponse;
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

/**
 * Maps inventory-svc domain records to the DTOs served over HTTP.
 *
 * <p>Domain records never cross the HTTP boundary; quantities and money stay {@code BigDecimal}
 * throughout, and ids are rendered as strings so the JSON contract stays stable.
 */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a level summary to its wire form.
   *
   * @param s the level summary to convert
   * @return its API representation
   */
  public static LevelSummaryResponse toLevelSummary(LevelSummary s) {
    return new LevelSummaryResponse(s.skuCount(), s.lowStockCount());
  }

  /**
   * Converts a level to its wire form.
   *
   * @param l the level to convert
   * @return its API representation
   */
  public static LevelResponse toLevel(Level l) {
    return new LevelResponse(
        l.storeId().toString(), l.variantId().toString(), l.onHand(), l.reserved(), l.available());
  }

  /**
   * Converts a batch to its wire form.
   *
   * @param b the batch to convert
   * @return its API representation
   */
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

  /**
   * Converts a reservation to its wire form.
   *
   * @param r the reservation to convert
   * @return its API representation
   */
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

  /**
   * Converts a low stock row to its wire form.
   *
   * @param r the low stock row to convert
   * @return its API representation
   */
  public static LowStockRowResponse toLowStockRow(LowStockRow r) {
    return new LowStockRowResponse(
        r.storeId(), r.variantId(), r.signal(), r.reorderLevel(), r.availableQty(), r.shortfall());
  }

  /**
   * Converts a valuation row to its wire form.
   *
   * @param r the valuation row to convert
   * @return its API representation
   */
  public static ValuationRowResponse toValuationRow(ValuationRow r) {
    return new ValuationRowResponse(
        r.groupKey(), r.method(), r.onHandQty(), r.unvaluedQty(), r.value());
  }

  /**
   * Converts a shrinkage row to its wire form.
   *
   * @param r the shrinkage row to convert
   * @return its API representation
   */
  public static ShrinkageRowResponse toShrinkageRow(ShrinkageRow r) {
    return new ShrinkageRowResponse(
        r.groupKey(), r.qtyWrittenOff(), r.qtyFound(), r.netQty(), r.movements());
  }

  /**
   * Converts a stock turn row to its wire form.
   *
   * @param r the stock turn row to convert
   * @return its API representation
   */
  public static StockTurnRowResponse toStockTurnRow(StockTurnRow r) {
    return new StockTurnRowResponse(
        r.groupKey(),
        r.cogs(),
        r.uncostedSaleQty(),
        r.openingValue(),
        r.closingValue(),
        r.averageValue(),
        r.turnoverRatio(),
        r.daysOnHand());
  }

  /**
   * Converts a stock turn report to its wire form.
   *
   * @param report the stock turn report to convert
   * @return its API representation
   */
  public static StockTurnReportResponse toStockTurnReport(StockTurnReport report) {
    return new StockTurnReportResponse(
        report.rows().stream().map(Mappers::toStockTurnRow).toList(),
        report.historyComplete(),
        report.windowDays());
  }

  /**
   * Converts a dead stock row to its wire form.
   *
   * @param r the dead stock row to convert
   * @return its API representation
   */
  public static DeadStockRowResponse toDeadStockRow(DeadStockRow r) {
    return new DeadStockRowResponse(
        r.groupKey(),
        r.onHandQty(),
        r.value(),
        r.uncostedQty(),
        r.daysSinceLastSale(),
        r.neverSold());
  }

  /**
   * Converts a movement to its wire form.
   *
   * @param m the movement to convert
   * @return its API representation
   */
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

  /**
   * Converts a threshold to its wire form.
   *
   * @param t the threshold to convert
   * @return its API representation
   */
  public static ThresholdResponse toThreshold(Threshold t) {
    return new ThresholdResponse(
        t.id().toString(),
        t.storeId().toString(),
        t.variantId().toString(),
        t.threshold(),
        t.maxQty());
  }

  /**
   * Converts a suggestion to its wire form.
   *
   * @param s the suggestion to convert
   * @return its API representation
   */
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

  /**
   * Converts a serial to its wire form.
   *
   * @param s the serial to convert
   * @return its API representation
   */
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

  /**
   * Converts a serial movement to its wire form.
   *
   * @param m the serial movement to convert
   * @return its API representation
   */
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

  /**
   * Converts a demand bucket to its wire form.
   *
   * @param b the demand bucket to convert
   * @return its API representation
   */
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

  /**
   * Converts a transfer order line to its wire form.
   *
   * @param l the transfer order line to convert
   * @return its API representation
   */
  public static TransferOrderLineResponse toTransferOrderLine(TransferOrderLine l) {
    return new TransferOrderLineResponse(
        l.id().toString(),
        l.variantId().toString(),
        l.requestedQty(),
        l.shippedQty(),
        l.receivedQty());
  }

  /**
   * Converts a transfer order to its wire form.
   *
   * @param o the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
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

  /**
   * Converts a move order line to its wire form.
   *
   * @param l the move order line to convert
   * @return its API representation
   */
  public static MoveOrderLineResponse toMoveOrderLine(MoveOrderLine l) {
    return new MoveOrderLineResponse(
        l.id().toString(), l.variantId().toString(), l.requestedQty(), l.pickedQty());
  }

  /**
   * Converts a move order to its wire form.
   *
   * @param o the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
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

  /**
   * Converts a lot link to its wire form.
   *
   * @param l the lot link to convert
   * @return its API representation
   */
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

  /**
   * Converts a cycle count line to its wire form.
   *
   * @param l the cycle count line to convert
   * @return its API representation
   */
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

  /**
   * Converts a cycle count header to its wire form.
   *
   * @param h the record to persist
   * @param lines the lines to store
   * @return its API representation
   */
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

  /**
   * Converts an abc compile run to its wire form.
   *
   * @param r the abc compile run to convert
   * @return its API representation
   */
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

  /**
   * Converts an abc assignment to its wire form.
   *
   * @param a the abc assignment to convert
   * @return its API representation
   */
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

  /**
   * Converts a safety stock params to its wire form.
   *
   * @param p the safety stock params to convert
   * @return its API representation
   */
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

  /**
   * Converts a tag to its wire form.
   *
   * @param t the tag to convert
   * @return its API representation
   */
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

  /**
   * Converts a physical inventory to its wire form.
   *
   * @param pi the record to persist
   * @param tags the count tags
   * @return its API representation
   */
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

  /**
   * Converts a rop plan to its wire form.
   *
   * @param p the rop plan to convert
   * @return its API representation
   */
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

  /**
   * Converts a kanban card to its wire form.
   *
   * @param k the kanban card to convert
   * @return its API representation
   */
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

  /**
   * Converts a costing method to its wire form.
   *
   * @param cm the costing method to convert
   * @return its API representation
   */
  public static CostingMethodResponse toCostingMethod(CostingMethod cm) {
    return new CostingMethodResponse(
        cm.id().toString(),
        cm.storeId().toString(),
        cm.variantId().toString(),
        cm.method(),
        cm.averageCost(),
        cm.updatedAt().toString());
  }

  /**
   * Converts a period to its wire form.
   *
   * @param ap the period to convert
   * @return its API representation
   */
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

  /**
   * Converts a reason code to its wire form.
   *
   * @param r the reason code to convert
   * @return its API representation
   */
  public static ReasonCodeResponse toReasonCode(ReasonCode r) {
    return new ReasonCodeResponse(
        r.id().toString(),
        r.tenantId() == null ? null : r.tenantId().toString(),
        r.code(),
        r.description(),
        r.active(),
        ts(r.createdAt()));
  }

  /**
   * Converts a source type to its wire form.
   *
   * @param t the source type to convert
   * @return its API representation
   */
  public static SourceTypeResponse toSourceType(TransactionSourceType t) {
    return new SourceTypeResponse(
        t.id().toString(),
        t.tenantId() == null ? null : t.tenantId().toString(),
        t.code(),
        t.description(),
        t.active(),
        ts(t.createdAt()));
  }

  /**
   * Converts a lot action to its wire form.
   *
   * @param a the lot action to convert
   * @return its API representation
   */
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

  /**
   * Converts an expiring batch to its wire form.
   *
   * @param b the expiring batch to convert
   * @return its API representation
   */
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

  /**
   * Converts a lot uom conversion to its wire form.
   *
   * @param c the lot uom conversion to convert
   * @return its API representation
   */
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

  /**
   * Converts a par level to its wire form.
   *
   * @param p the par level to convert
   * @return its API representation
   */
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

  /**
   * Converts a zone gl mapping to its wire form.
   *
   * @param z the zone gl mapping to convert
   * @return its API representation
   */
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

  /**
   * Converts a picking rule to its wire form.
   *
   * @param r the picking rule to convert
   * @return its API representation
   */
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

  /**
   * Converts a zone priority to its wire form.
   *
   * @param p the zone priority to convert
   * @return its API representation
   */
  public static PickingRuleZonePriorityResponse toZonePriority(PickingRuleZonePriority p) {
    return new PickingRuleZonePriorityResponse(
        p.id().toString(), p.zoneId().toString(), p.priority());
  }

  /**
   * Converts a picking rule assignment to its wire form.
   *
   * @param a the picking rule assignment to convert
   * @return its API representation
   */
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
