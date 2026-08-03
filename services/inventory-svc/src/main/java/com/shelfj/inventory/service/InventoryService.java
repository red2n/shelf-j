package com.shelfj.inventory.service;

import com.shelfj.inventory.config.ServiceConfig;
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
import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.domain.Domain.TransactionSourceType;
import com.shelfj.inventory.domain.Domain.TransferOrder;
import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.inventory.domain.Domain.ZoneGlMapping;
import com.shelfj.inventory.repo.AbcAnalysisRepository;
import com.shelfj.inventory.repo.CostingRepository;
import com.shelfj.inventory.repo.CycleCountRepository;
import com.shelfj.inventory.repo.DemandHistoryRepository;
import com.shelfj.inventory.repo.InventoryRepository;
import com.shelfj.inventory.repo.KanbanRepository;
import com.shelfj.inventory.repo.LotActionRepository;
import com.shelfj.inventory.repo.LotGenealogyRepository;
import com.shelfj.inventory.repo.MovementArchiveRepository;
import com.shelfj.inventory.repo.MovementRepository;
import com.shelfj.inventory.repo.PhysicalInventoryRepository;
import com.shelfj.inventory.repo.PickingRuleRepository;
import com.shelfj.inventory.repo.PlanningConfigRepository;
import com.shelfj.inventory.repo.ReferenceDataRepository;
import com.shelfj.inventory.repo.ReorderPointRepository;
import com.shelfj.inventory.repo.SafetyStockRepository;
import com.shelfj.inventory.repo.SerialRepository;
import com.shelfj.inventory.repo.SuggestionRepository;
import com.shelfj.inventory.repo.ThresholdRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stock business logic. All mutations emit a stock event via the outbox (golden rule #6). */
@ApplicationScoped
public class InventoryService {

  @Inject ServiceConfig config;
  @Inject InventoryRepository repo;
  @Inject LotGenealogyRepository lotGenealogyRepo;
  @Inject ThresholdRepository thresholdRepo;
  @Inject SuggestionRepository suggestionRepo;
  @Inject DemandHistoryRepository demandHistoryRepo;
  @Inject CycleCountRepository cycleCountRepo;
  @Inject AbcAnalysisRepository abcRepo;
  @Inject SafetyStockRepository safetyStockRepo;
  @Inject MovementRepository movementRepo;
  @Inject PhysicalInventoryRepository physicalInventoryRepo;
  @Inject ReorderPointRepository ropRepo;
  @Inject KanbanRepository kanbanRepo;
  @Inject CostingRepository costingRepo;
  @Inject LotActionRepository lotActionRepo;
  @Inject MovementArchiveRepository movementArchiveRepo;
  @Inject PickingRuleRepository pickingRuleRepo;
  @Inject SerialRepository serialRepo;
  @Inject ReferenceDataRepository refData;
  @Inject PlanningConfigRepository planningConfig;

  // ---- receive (manual GRN entry; event-driven receives go through receiveOnce instead) ----
  public Batch receive(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId,
      UUID zoneId,
      String idempotencyKey) {
    UUID batchId = UUID.randomUUID();
    var batch =
        new Batch(
            batchId,
            tenantId,
            storeId,
            variantId,
            batchNo,
            qty,
            qty,
            costPrice,
            expiry,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            zoneId);
    var event =
        new OutboxRow(
            "StockReceived",
            "shelfj.inventory.stock-received",
            tenantId,
            batchId,
            Events.stockReceived(tenantId, storeId, variantId, batchId, qty));
    try {
      return repo.receive(batch, refType, refId, event, idempotencyKey);
    } catch (ApiException e) {
      // Idempotent replay: a retried receipt with the same key gets the original batch back
      // instead of double-counting stock (golden rule #11).
      if ("BATCH_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findBatchByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      throw e;
    }
  }

  // ---- Gap #50: POS→SIM deduction (order fulfilled) ----
  public void deductSaleFromOrder(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    repo.deductSale(
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        stockDeductedEvent(tenantId, storeId, variantId, qty, orderId));
  }

  /**
   * {@link #deductSaleFromOrder} deduped on {@code dedupeId} — used by event consumers so the
   * dedupe mark and the deduction commit atomically (a redelivered event line is skipped, a crashed
   * one retried).
   */
  public boolean deductSaleFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId) {
    return repo.deductSaleOnce(
        dedupeId,
        consumerName,
        tenantId,
        storeId,
        variantId,
        qty,
        orderId,
        stockDeductedEvent(tenantId, storeId, variantId, qty, orderId));
  }

  private static OutboxRow stockDeductedEvent(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    return new OutboxRow(
        "StockDeducted",
        "shelfj.inventory.stock-deducted",
        tenantId,
        orderId,
        Events.stockDeducted(tenantId, storeId, variantId, orderId, qty));
  }

  // ---- Gap #50: POS→SIM receipt (order returned) ----
  public void receiveReturnFromOrder(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    Batch batch = returnBatch(tenantId, storeId, variantId, qty, orderId);
    repo.receive(batch, "RETURN", orderId, stockReceivedEvent(batch), null);
  }

  /** {@link #receiveReturnFromOrder} deduped on {@code dedupeId} (see deductSaleFromOrderOnce). */
  public boolean receiveReturnFromOrderOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId) {
    Batch batch = returnBatch(tenantId, storeId, variantId, qty, orderId);
    return repo.receiveOnce(
        dedupeId, consumerName, batch, "RETURN", orderId, stockReceivedEvent(batch));
  }

  /**
   * {@link #receive} deduped on {@code dedupeId} — used by the GoodsReceived consumer per GRN line.
   */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId) {
    var batch =
        new Batch(
            UUID.randomUUID(),
            tenantId,
            storeId,
            variantId,
            batchNo,
            qty,
            qty,
            costPrice,
            expiry,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            null);
    return repo.receiveOnce(
        dedupeId, consumerName, batch, refType, refId, stockReceivedEvent(batch));
  }

  private static Batch returnBatch(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId) {
    return new Batch(
        UUID.randomUUID(),
        tenantId,
        storeId,
        variantId,
        "RET-" + orderId.toString().substring(0, 8),
        qty,
        qty,
        null,
        null,
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        null,
        null);
  }

  private static OutboxRow stockReceivedEvent(Batch batch) {
    return new OutboxRow(
        "StockReceived",
        "shelfj.inventory.stock-received",
        batch.tenantId(),
        batch.id(),
        Events.stockReceived(
            batch.tenantId(), batch.storeId(), batch.variantId(), batch.id(), batch.receivedQty()));
  }

  // ---- adjust ----
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      String idempotencyKey) {
    var event =
        new OutboxRow(
            "StockAdjusted",
            "shelfj.inventory.stock-adjusted",
            tenantId,
            variantId,
            Events.stockAdjusted(tenantId, storeId, variantId, delta));
    repo.adjust(tenantId, storeId, variantId, delta, reason, event, idempotencyKey);
  }

  // ---- reserve ----
  public Reservation reserve(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      Long ttlSeconds,
      String idempotencyKey) {
    long ttl = ttlSeconds == null ? config.reservationTtlSeconds() : ttlSeconds;
    UUID id = UUID.randomUUID();
    var reservation =
        new Reservation(
            id,
            tenantId,
            storeId,
            variantId,
            qty,
            orderId,
            Reservation.HELD,
            Instant.now().plusSeconds(ttl),
            Instant.now());
    var event =
        new OutboxRow(
            "StockReserved",
            "shelfj.inventory.stock-reserved",
            tenantId,
            id,
            Events.stockReserved(tenantId, storeId, variantId, id, qty));
    try {
      return repo.reserve(reservation, event, idempotencyKey);
    } catch (ApiException e) {
      // Idempotent replay: a retried reservation with the same key gets the original hold back
      // instead of holding stock twice for one checkout attempt (golden rule #11).
      if ("RESERVATION_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findReservationByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      throw e;
    }
  }

  // ---- consume (FIFO deduct) ----
  public void consume(UUID tenantId, UUID reservationId) {
    repo.consume(tenantId, reservationId);
  }

  /**
   * {@link #consume} deduped on {@code dedupeId} — used by the OrderFulfilled consumer so the
   * dedupe mark and the consumption commit atomically (a redelivered event line is skipped, a
   * crashed one retried).
   */
  public boolean consumeOnce(
      UUID dedupeId, String consumerName, UUID tenantId, UUID reservationId) {
    return repo.consumeOnce(dedupeId, consumerName, tenantId, reservationId);
  }

  /** The HELD reservations placed for one order at checkout. */
  public List<Reservation> heldReservationsByOrder(UUID tenantId, UUID orderId) {
    return repo.heldReservationsByOrder(tenantId, orderId);
  }

  // ---- release ----
  public boolean release(UUID tenantId, UUID reservationId) {
    var event =
        new OutboxRow(
            "StockReleased",
            "shelfj.inventory.stock-released",
            tenantId,
            reservationId,
            Events.reservationEvent("StockReleased", tenantId, reservationId));
    return repo.release(tenantId, reservationId, event);
  }

  // ---- reads ----
  public List<Level> levels(UUID tenantId, UUID storeId) {
    return repo.levels(tenantId, storeId);
  }

  /** One page of stock levels plus the opaque cursor for the next page (null when exhausted). */
  public record LevelPage(List<Level> levels, String nextCursor) {}

  /**
   * Cursor-paginated levels. The cursor wraps the last row's {@code storeId|variantId} keyset; a
   * fresh call (null cursor) starts at the first row. Fetches one extra row to learn whether a
   * further page exists without a second query.
   */
  public LevelPage levelsPage(UUID tenantId, UUID storeId, String afterCursor, int limit) {
    UUID afterStoreId = null;
    UUID afterVariantId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) {
          throw new IllegalArgumentException("missing separator");
        }
        afterStoreId = UUID.fromString(rawKey.substring(0, sep));
        afterVariantId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<Level> rows = repo.levelsPage(tenantId, storeId, afterStoreId, afterVariantId, limit + 1);
    if (rows.size() <= limit) {
      return new LevelPage(rows, null);
    }
    List<Level> page = rows.subList(0, limit);
    Level last = page.get(page.size() - 1);
    return new LevelPage(
        page, com.shelfj.web.Cursor.encode(last.storeId() + "|" + last.variantId()));
  }

  /**
   * SKUs with available quantity at or below this count as "low stock" for the dashboard summary,
   * mirroring the client's {@code InventoryLevel.isLow} heuristic (available &lt;= 5).
   */
  private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

  /** Aggregate SKU / low-stock counts for the dashboard, without materializing the full list. */
  public LevelSummary levelsSummary(UUID tenantId, UUID storeId) {
    return repo.levelsSummary(tenantId, storeId, LOW_STOCK_THRESHOLD);
  }

  public List<Batch> listBatches(
      UUID tenantId, UUID storeId, UUID variantId, String materialStatus, int limit) {
    return repo.listBatches(tenantId, storeId, variantId, materialStatus, limit);
  }

  public Batch updateMaterialStatus(
      UUID tenantId, UUID batchId, String materialStatus, String reason) {
    if (!List.of(
            Batch.MATERIAL_AVAILABLE,
            Batch.MATERIAL_QUARANTINE,
            Batch.MATERIAL_INSPECTION,
            Batch.MATERIAL_DAMAGED,
            Batch.MATERIAL_RECALLED)
        .contains(materialStatus)) {
      throw new ApiException(
          400,
          "INVALID_MATERIAL_STATUS",
          "materialStatus must be AVAILABLE|QUARANTINE|INSPECTION|DAMAGED|RECALLED",
          List.of(),
          null);
    }
    var event =
        new OutboxRow(
            "MaterialStatusChanged",
            "shelfj.inventory.material-status-changed",
            tenantId,
            batchId,
            Events.materialStatusChanged(tenantId, batchId, materialStatus, reason));
    return repo.updateMaterialStatus(tenantId, batchId, materialStatus, reason, event);
  }

  public Batch getBatch(UUID tenantId, UUID batchId) {
    return repo.getBatch(tenantId, batchId)
        .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "No such batch"));
  }

  public List<Movement> listMovements(
      UUID tenantId, UUID storeId, UUID variantId, String type, int limit) {
    return movementRepo.listMovements(tenantId, storeId, variantId, type, limit);
  }

  public List<Reservation> listReservations(UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listReservations(tenantId, storeId, status, limit);
  }

  public Reservation getReservation(UUID tenantId, UUID reservationId) {
    return repo.findReservation(tenantId, reservationId)
        .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "No such reservation"));
  }

  public Threshold setThreshold(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal threshold, BigDecimal maxQty) {
    return thresholdRepo.upsertThreshold(
        new Threshold(UUID.randomUUID(), tenantId, storeId, variantId, threshold, maxQty));
  }

  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    return thresholdRepo.listThresholds(tenantId, storeId);
  }

  // ---- min-max planning engine ----

  /**
   * Scan every threshold for the tenant (optionally filtered by store), compare against current
   * available stock, and create OPEN replenishment suggestions for any under-stocked SKU. Skips
   * SKUs that already have an OPEN suggestion (idempotent).
   */
  public List<Suggestion> runMinMaxPlan(UUID tenantId, UUID storeId) {
    List<Level> levels = repo.levels(tenantId, storeId);
    Map<String, BigDecimal> avail = new java.util.HashMap<>();
    for (Level l : levels) {
      avail.put(l.storeId() + ":" + l.variantId(), l.available());
    }

    List<Threshold> thresholds = thresholdRepo.listThresholds(tenantId, storeId);
    List<Suggestion> created = new ArrayList<>();
    for (Threshold t : thresholds) {
      BigDecimal available = avail.getOrDefault(t.storeId() + ":" + t.variantId(), BigDecimal.ZERO);
      if (available.compareTo(t.threshold()) >= 0) continue;

      BigDecimal target =
          t.maxQty() != null ? t.maxQty() : t.threshold().multiply(BigDecimal.valueOf(2));
      BigDecimal suggestedQty = target.subtract(available).max(BigDecimal.ONE);
      UUID suggId = UUID.randomUUID();
      var sugg =
          new Suggestion(
              suggId,
              tenantId,
              t.storeId(),
              t.variantId(),
              available,
              t.threshold(),
              t.maxQty(),
              suggestedQty,
              Suggestion.STATUS_OPEN,
              Instant.now(),
              null);
      var event =
          new OutboxRow(
              "ReplenishmentSuggested",
              "shelfj.inventory.replenishment-suggested",
              tenantId,
              suggId,
              Events.replenishmentSuggested(
                  tenantId, suggId, t.storeId(), t.variantId(), suggestedQty));
      suggestionRepo.insertSuggestionIfAbsent(sugg, event).ifPresent(created::add);
    }
    return created;
  }

  public List<Suggestion> listSuggestions(UUID tenantId, UUID storeId, String status, int limit) {
    return suggestionRepo.listSuggestions(tenantId, storeId, status, limit);
  }

  public Suggestion resolveSuggestion(UUID tenantId, UUID suggId, String newStatus) {
    if (!List.of(Suggestion.STATUS_ORDERED, Suggestion.STATUS_CANCELLED).contains(newStatus)) {
      throw new ApiException(
          400, "INVALID_SUGGESTION_STATUS", "status must be ORDERED or CANCELLED", List.of(), null);
    }
    var event =
        new OutboxRow(
            "ReplenishmentResolved",
            "shelfj.inventory.replenishment-resolved",
            tenantId,
            suggId,
            Events.replenishmentResolved(tenantId, suggId, newStatus));
    return suggestionRepo
        .resolveSuggestion(tenantId, suggId, newStatus, event)
        .orElseThrow(
            () -> ApiException.notFound("SUGGESTION_NOT_FOUND", "No open suggestion with that id"));
  }

  // ---- serial number control (Gap #3) ----

  public List<SerialNumber> registerSerials(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      List<String> serials,
      Integer autoQty,
      String prefix) {
    List<String> serialNos;
    if (serials != null && !serials.isEmpty()) {
      if (serials.size() > 200)
        throw new ApiException(
            400, "TOO_MANY_SERIALS", "max 200 serials per call", List.of(), null);
      serialNos = serials;
    } else if (autoQty != null && autoQty > 0) {
      if (autoQty > 200)
        throw new ApiException(400, "TOO_MANY_SERIALS", "autoQty max 200", List.of(), null);
      String pfx = prefix == null || prefix.isBlank() ? "SN" : prefix;
      serialNos = new ArrayList<>();
      for (int i = 0; i < autoQty; i++) {
        serialNos.add(generateSerialNo(pfx, i));
      }
    } else {
      throw new ApiException(
          400, "SERIALS_REQUIRED", "provide serials list or autoQty > 0", List.of(), null);
    }
    Instant now = Instant.now();
    var domainSerials =
        serialNos.stream()
            .map(
                sno ->
                    new SerialNumber(
                        UUID.randomUUID(),
                        tenantId,
                        storeId,
                        variantId,
                        batchId,
                        sno,
                        SerialNumber.IN_STOCK,
                        now,
                        null))
            .toList();
    var event =
        new OutboxRow(
            "SerialsRegistered",
            "shelfj.inventory.serials-registered",
            tenantId,
            batchId,
            Events.serialsRegistered(tenantId, batchId, domainSerials.size()));
    return serialRepo.registerSerials(domainSerials, event);
  }

  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    return serialRepo.listSerials(tenantId, storeId, variantId, status, limit);
  }

  public SerialNumber getSerial(UUID tenantId, UUID serialId) {
    return serialRepo
        .findSerial(tenantId, serialId)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  public SerialNumber lookupSerialByNo(UUID tenantId, String serialNo) {
    return serialRepo
        .findSerialByNo(tenantId, serialNo)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  public SerialNumber updateSerialStatus(UUID tenantId, UUID serialId, String newStatus) {
    if (!List.of(
            SerialNumber.IN_STOCK,
            SerialNumber.RESERVED,
            SerialNumber.SOLD,
            SerialNumber.RETURNED,
            SerialNumber.LOST,
            SerialNumber.DAMAGED)
        .contains(newStatus)) {
      throw new ApiException(
          400,
          "INVALID_SERIAL_STATUS",
          "status must be IN_STOCK|RESERVED|SOLD|RETURNED|LOST|DAMAGED",
          List.of(),
          null);
    }
    var event =
        new OutboxRow(
            "SerialStatusChanged",
            "shelfj.inventory.serial-status-changed",
            tenantId,
            serialId,
            Events.serialStatusChanged(tenantId, serialId, newStatus));
    return serialRepo
        .updateSerialStatus(tenantId, serialId, newStatus, event)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return serialRepo.listSerialHistory(tenantId, serialId);
  }

  private static String generateSerialNo(String prefix, int index) {
    String rand =
        Long.toHexString(System.nanoTime() ^ ((long) index * 0x9E3779B97F4A7C15L))
            .toUpperCase(Locale.ROOT)
            .substring(0, 8);
    return prefix + "-" + rand;
  }

  // ---- demand history (Gap #7) ----

  public int aggregateDemand(UUID tenantId, UUID storeId, String bucketType, LocalDate since) {
    String bt = bucketType == null ? DemandBucket.BUCKET_WEEK : bucketType.toUpperCase(Locale.ROOT);
    if (!List.of(DemandBucket.BUCKET_DAY, DemandBucket.BUCKET_WEEK, DemandBucket.BUCKET_MONTH)
        .contains(bt)) {
      throw new ApiException(
          400, "INVALID_BUCKET_TYPE", "bucketType must be DAY, WEEK, or MONTH", List.of(), null);
    }
    return demandHistoryRepo.aggregateDemand(tenantId, storeId, bt, since);
  }

  public List<DemandBucket> listDemandHistory(
      UUID tenantId, UUID storeId, UUID variantId, String bucketType, int limit) {
    String bt = bucketType == null ? null : bucketType.toUpperCase(Locale.ROOT);
    return demandHistoryRepo.listDemandHistory(tenantId, storeId, variantId, bt, limit);
  }

  // ---- move orders (Gap #5) ----

  public MoveOrder createMoveOrder(
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String fromZone,
      String toZone,
      String notes,
      List<MoveOrderLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ApiException(
          400, "NO_LINES", "Move order must have at least one line", List.of(), null);
    }
    UUID orderId = UUID.randomUUID();
    Instant now = Instant.now();
    MoveOrder order =
        new MoveOrder(
            orderId,
            tenantId,
            fromStoreId,
            toStoreId,
            fromZone,
            toZone,
            notes,
            MoveOrder.DRAFT,
            now,
            null);
    List<MoveOrderLine> withIds =
        lines.stream()
            .map(
                l ->
                    new MoveOrderLine(
                        UUID.randomUUID(),
                        tenantId,
                        orderId,
                        l.variantId(),
                        l.requestedQty(),
                        null))
            .toList();
    return repo.createMoveOrder(order, withIds);
  }

  public List<MoveOrder> listMoveOrders(UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listMoveOrders(tenantId, storeId, status, limit);
  }

  public record MoveOrderWithLines(MoveOrder order, List<MoveOrderLine> lines) {}

  public MoveOrderWithLines getMoveOrder(UUID tenantId, UUID id) {
    MoveOrder order =
        repo.findMoveOrder(tenantId, id)
            .orElseThrow(() -> ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order"));
    return new MoveOrderWithLines(order, repo.listMoveOrderLines(id));
  }

  public MoveOrderWithLines pickMoveOrder(UUID tenantId, UUID id) {
    MoveOrder existing =
        repo.findMoveOrder(tenantId, id)
            .orElseThrow(() -> ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order"));
    MoveOrder picked =
        repo.pickMoveOrder(
            tenantId,
            id,
            new OutboxRow(
                "MoveOrderCompleted",
                "shelfj.inventory.move-order-completed",
                tenantId,
                id,
                Events.moveOrderCompleted(
                    tenantId, id, existing.fromStoreId(), existing.toStoreId())));
    return new MoveOrderWithLines(picked, repo.listMoveOrderLines(id));
  }

  public MoveOrder cancelMoveOrder(UUID tenantId, UUID id) {
    return repo.cancelMoveOrder(
            tenantId,
            id,
            new OutboxRow(
                "MoveOrderCancelled",
                "shelfj.inventory.move-order-cancelled",
                tenantId,
                id,
                Events.moveOrderCancelled(tenantId, id)))
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "MOVE_ORDER_NOT_CANCELLABLE",
                    "Move order cannot be cancelled in its current state"));
  }

  // ---- transfer orders (Gap #6) ----

  public record TransferOrderWithLines(TransferOrder order, List<TransferOrderLine> lines) {}

  public TransferOrderWithLines createTransferOrder(
      UUID tenantId,
      UUID fromStoreId,
      UUID toStoreId,
      String transferType,
      String notes,
      List<TransferOrderLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ApiException(
          400, "NO_LINES", "Transfer order must have at least one line", List.of(), null);
    }
    String type =
        transferType == null ? TransferOrder.TYPE_DIRECT : transferType.toUpperCase(Locale.ROOT);
    if (!List.of(TransferOrder.TYPE_DIRECT, TransferOrder.TYPE_INTRANSIT).contains(type)) {
      throw new ApiException(
          400,
          "INVALID_TRANSFER_TYPE",
          "transferType must be DIRECT or INTRANSIT",
          List.of(),
          null);
    }
    UUID orderId = UUID.randomUUID();
    Instant now = Instant.now();
    TransferOrder order =
        new TransferOrder(
            orderId,
            tenantId,
            fromStoreId,
            toStoreId,
            type,
            TransferOrder.PENDING,
            notes,
            now,
            null,
            null);
    List<TransferOrderLine> withIds =
        lines.stream()
            .map(
                l ->
                    new TransferOrderLine(
                        UUID.randomUUID(),
                        tenantId,
                        orderId,
                        l.variantId(),
                        l.requestedQty(),
                        null,
                        null))
            .toList();
    repo.createTransferOrder(order, withIds);
    return new TransferOrderWithLines(order, repo.listTransferOrderLines(orderId));
  }

  public List<TransferOrder> listTransferOrders(
      UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listTransferOrders(tenantId, storeId, status, limit);
  }

  public TransferOrderWithLines getTransferOrder(UUID tenantId, UUID id) {
    TransferOrder order =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    return new TransferOrderWithLines(order, repo.listTransferOrderLines(id));
  }

  public TransferOrderWithLines shipTransferOrder(UUID tenantId, UUID id) {
    TransferOrder existing =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    TransferOrder shipped =
        repo.shipTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderShipped",
                "shelfj.inventory.transfer-order-shipped",
                tenantId,
                id,
                Events.transferOrderShipped(
                    tenantId, id, existing.fromStoreId(), existing.toStoreId())));
    return new TransferOrderWithLines(shipped, repo.listTransferOrderLines(id));
  }

  public TransferOrderWithLines receiveTransferOrder(UUID tenantId, UUID id) {
    TransferOrder existing =
        repo.findTransferOrder(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order"));
    TransferOrder received =
        repo.receiveTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderReceived",
                "shelfj.inventory.transfer-order-received",
                tenantId,
                id,
                Events.transferOrderReceived(tenantId, id, existing.toStoreId())));
    return new TransferOrderWithLines(received, repo.listTransferOrderLines(id));
  }

  public TransferOrder cancelTransferOrder(UUID tenantId, UUID id) {
    return repo.cancelTransferOrder(
            tenantId,
            id,
            new OutboxRow(
                "TransferOrderCancelled",
                "shelfj.inventory.transfer-order-cancelled",
                tenantId,
                id,
                Events.transferOrderCancelled(tenantId, id)))
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "TRANSFER_ORDER_NOT_CANCELLABLE",
                    "Only PENDING transfer orders can be cancelled"));
  }

  // ---- cycle counting (Gap #10) ----

  public record CycleCountWithLines(CycleCountHeader header, List<CycleCountLine> lines) {}

  /**
   * Create a cycle count for a store. Lines are generated from ABC assignments matching abcClasses
   * (defaults to all). Each line captures the current system on-hand qty as a snapshot. If no ABC
   * assignments exist, no lines are generated (count proceeds as manual).
   */
  public CycleCountWithLines createCycleCount(
      UUID tenantId, UUID storeId, String name, String abcClasses, BigDecimal tolerancePct) {

    String classes =
        abcClasses == null || abcClasses.isBlank() ? "A,B,C" : abcClasses.toUpperCase(Locale.ROOT);
    BigDecimal tol = tolerancePct == null ? BigDecimal.valueOf(5) : tolerancePct;
    if (tol.compareTo(BigDecimal.ZERO) < 0 || tol.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new ApiException(
          400, "INVALID_TOLERANCE", "tolerancePct must be 0–100", List.of(), null);
    }

    UUID headerId = UUID.randomUUID();
    Instant now = Instant.now();
    CycleCountHeader header =
        new CycleCountHeader(
            headerId, tenantId, storeId, name, classes, tol, CycleCountHeader.OPEN, now, null);

    // Generate lines from ABC assignments that match requested classes
    List<String> requestedClasses = List.of(classes.split(","));
    List<AbcAssignment> assignments = abcRepo.listAbcAssignments(tenantId, storeId, null, 1000);
    // Fetch all on-hand quantities in one query instead of one per variant (avoids N+1).
    List<UUID> matchingVariantIds =
        assignments.stream()
            .filter(a -> requestedClasses.contains(a.abcClass()))
            .map(AbcAssignment::variantId)
            .toList();
    Map<UUID, BigDecimal> onHandMap =
        cycleCountRepo.onHandQtyBatch(tenantId, storeId, matchingVariantIds);
    List<CycleCountLine> lines = new ArrayList<>();
    for (AbcAssignment a : assignments) {
      if (!requestedClasses.contains(a.abcClass())) continue;
      BigDecimal onHand = onHandMap.getOrDefault(a.variantId(), BigDecimal.ZERO);
      lines.add(
          new CycleCountLine(
              UUID.randomUUID(),
              tenantId,
              headerId,
              storeId,
              a.variantId(),
              onHand,
              null,
              null,
              null,
              CycleCountLine.OPEN,
              null));
    }

    cycleCountRepo.createCycleCountHeader(header, lines);
    return new CycleCountWithLines(header, lines);
  }

  public List<CycleCountWithLines> listCycleCounts(
      UUID tenantId, UUID storeId, String status, int limit) {
    List<CycleCountHeader> headers =
        cycleCountRepo.listCycleCountHeaders(tenantId, storeId, status, limit);
    List<UUID> headerIds = headers.stream().map(CycleCountHeader::id).toList();
    Map<UUID, List<CycleCountLine>> linesByHeader =
        cycleCountRepo.listCycleCountLinesByHeaders(headerIds);
    return headers.stream()
        .map(h -> new CycleCountWithLines(h, linesByHeader.getOrDefault(h.id(), List.of())))
        .toList();
  }

  public CycleCountWithLines getCycleCount(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    return new CycleCountWithLines(header, cycleCountRepo.listCycleCountLines(headerId));
  }

  /** Record the physically counted qty for one line; computes variance. */
  public CycleCountLine enterCount(
      UUID tenantId, UUID headerId, UUID lineId, BigDecimal countedQty) {
    if (countedQty.signum() < 0) {
      throw new ApiException(400, "INVALID_COUNT", "countedQty must be >= 0", List.of(), null);
    }
    // Verify line belongs to this header + tenant
    CycleCountLine existing =
        cycleCountRepo
            .findCycleCountLine(tenantId, lineId)
            .orElseThrow(() -> ApiException.notFound("COUNT_LINE_NOT_FOUND", "No such count line"));
    if (!existing.headerId().equals(headerId)) {
      throw new ApiException(
          400, "LINE_HEADER_MISMATCH", "Line does not belong to this count", List.of(), null);
    }
    BigDecimal variance = countedQty.subtract(existing.systemQty());
    BigDecimal variancePct =
        existing.systemQty().compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.valueOf(100)
            : variance
                .abs()
                .divide(existing.systemQty(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

    // Advance header to IN_PROGRESS if still OPEN
    cycleCountRepo
        .findCycleCountHeader(tenantId, headerId)
        .ifPresent(
            h -> {
              if (CycleCountHeader.OPEN.equals(h.status())) {
                cycleCountRepo.updateHeaderStatus(tenantId, headerId, CycleCountHeader.IN_PROGRESS);
              }
            });

    return cycleCountRepo
        .enterCount(tenantId, lineId, countedQty, variance, variancePct)
        .orElseThrow(
            () ->
                ApiException.unprocessable(
                    "COUNT_LINE_NOT_UPDATABLE", "Line cannot be updated in its current state"));
  }

  public record ApproveResult(int autoApproved, int flagged) {}

  /**
   * For all COUNTED lines: if |variancePct| <= tolerancePct → APPROVED; else REJECTED (awaits
   * manual override or re-count). Advances header to PENDING_APPROVAL if any are flagged, or
   * directly to ADJUSTED-ready state.
   */
  public ApproveResult approveWithTolerance(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    if (CycleCountHeader.ADJUSTED.equals(header.status())
        || CycleCountHeader.CLOSED.equals(header.status())) {
      throw new ApiException(
          422, "CYCLE_COUNT_CLOSED", "Cycle count is already " + header.status(), List.of(), null);
    }

    List<CycleCountLine> lines = cycleCountRepo.listCycleCountLines(headerId);
    List<UUID> toApprove = new ArrayList<>();
    List<UUID> toFlag = new ArrayList<>();
    for (CycleCountLine l : lines) {
      if (!CycleCountLine.COUNTED.equals(l.status())) continue;
      BigDecimal absPct = l.variancePct() == null ? BigDecimal.ZERO : l.variancePct().abs();
      if (absPct.compareTo(header.tolerancePct()) <= 0) toApprove.add(l.id());
      else toFlag.add(l.id());
    }
    cycleCountRepo.bulkUpdateLineStatus(headerId, toApprove, CycleCountLine.APPROVED);
    cycleCountRepo.bulkUpdateLineStatus(headerId, toFlag, CycleCountLine.REJECTED);

    String newHeaderStatus =
        toFlag.isEmpty() ? CycleCountHeader.IN_PROGRESS : CycleCountHeader.PENDING_APPROVAL;
    cycleCountRepo.updateHeaderStatus(tenantId, headerId, newHeaderStatus);
    return new ApproveResult(toApprove.size(), toFlag.size());
  }

  /** Apply stock adjustments for all APPROVED lines, then close the count header. */
  public int adjustCycleCount(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        cycleCountRepo
            .findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    if (CycleCountHeader.ADJUSTED.equals(header.status())
        || CycleCountHeader.CLOSED.equals(header.status())) {
      throw new ApiException(
          422, "CYCLE_COUNT_CLOSED", "Cycle count is already closed", List.of(), null);
    }
    var event =
        new OutboxRow(
            "CycleCountAdjusted",
            "shelfj.inventory.cycle-count-adjusted",
            tenantId,
            headerId,
            Events.cycleCountAdjusted(tenantId, headerId));
    return repo.applyAdjustments(tenantId, headerId, event);
  }

  // ---- Lot Genealogy (Gap #11) ----

  public LotGenealogyLink createLotLink(
      UUID tenantId,
      UUID parentBatchId,
      UUID childBatchId,
      BigDecimal qty,
      String relationType,
      String notes) {
    String type =
        relationType != null && !relationType.isBlank() ? relationType : LotGenealogyLink.SPLIT;
    if (!List.of(LotGenealogyLink.SPLIT, LotGenealogyLink.MERGE, LotGenealogyLink.TRANSFORM)
        .contains(type)) {
      throw new ApiException(
          400,
          "INVALID_RELATION_TYPE",
          "relationType must be SPLIT, MERGE, or TRANSFORM",
          List.of(),
          null);
    }
    var link =
        new LotGenealogyLink(
            UUID.randomUUID(),
            tenantId,
            parentBatchId,
            childBatchId,
            qty,
            type,
            notes,
            Instant.now());
    return lotGenealogyRepo.createLotLink(link);
  }

  public List<LotGenealogyLink> findAncestors(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findAncestors(tenantId, batchId);
  }

  public List<LotGenealogyLink> findDescendants(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findDescendants(tenantId, batchId);
  }

  public List<LotGenealogyLink> findDirectLinks(UUID tenantId, UUID batchId) {
    return lotGenealogyRepo.findDirectLinks(tenantId, batchId);
  }

  // ---- ABC analysis (Gap #9) ----

  /**
   * Run the ABC compile: score all variants in the tenant (optionally one store), rank descending,
   * and assign A/B/C using cumulative-value thresholds. Persists one AbcCompileRun + N
   * AbcAssignment rows (upserted — re-running overwrites previous).
   *
   * <p>Criteria VALUE: score = total_demand_qty * avg_cost_price (annual usage value). Criteria
   * VELOCITY: score = total_demand_qty (movement frequency only). Thresholds are cumulative % of
   * total score: A = 0..thresholdA, B = thresholdA..thresholdAB, C = rest.
   */
  public record AbcCompileResult(AbcCompileRun run, List<AbcAssignment> assignments) {}

  public AbcCompileResult runAbcCompile(
      UUID tenantId, UUID storeId, String criteria, BigDecimal thresholdA, BigDecimal thresholdAB) {

    String crit =
        criteria == null ? AbcCompileRun.CRITERIA_VALUE : criteria.toUpperCase(Locale.ROOT);
    if (!List.of(AbcCompileRun.CRITERIA_VALUE, AbcCompileRun.CRITERIA_VELOCITY).contains(crit)) {
      throw new ApiException(
          400, "INVALID_ABC_CRITERIA", "criteria must be VALUE or VELOCITY", List.of(), null);
    }
    BigDecimal tA = thresholdA == null ? BigDecimal.valueOf(70) : thresholdA;
    BigDecimal tAB = thresholdAB == null ? BigDecimal.valueOf(90) : thresholdAB;
    if (tA.compareTo(BigDecimal.ZERO) <= 0
        || tA.compareTo(BigDecimal.valueOf(100)) >= 0
        || tAB.compareTo(tA) <= 0
        || tAB.compareTo(BigDecimal.valueOf(100)) >= 0) {
      throw new ApiException(
          400, "INVALID_ABC_THRESHOLDS", "0 < thresholdA < thresholdAB < 100", List.of(), null);
    }

    List<Object[]> raw = abcRepo.abcScoringData(tenantId, storeId);
    if (raw.isEmpty()) {
      UUID runId = UUID.randomUUID();
      AbcCompileRun emptyRun =
          new AbcCompileRun(runId, tenantId, storeId, crit, tA, tAB, 0, Instant.now());
      abcRepo.persistAbcRun(emptyRun, List.of());
      return new AbcCompileResult(emptyRun, List.of());
    }

    // Compute score per row
    record Scored(UUID storeId, UUID variantId, BigDecimal score) {}
    List<Scored> scored = new ArrayList<>();
    for (Object[] row : raw) {
      UUID sid = (UUID) row[0];
      UUID vid = (UUID) row[1];
      BigDecimal demand = (BigDecimal) row[2];
      BigDecimal cost = (BigDecimal) row[3];
      BigDecimal s = AbcCompileRun.CRITERIA_VALUE.equals(crit) ? demand.multiply(cost) : demand;
      scored.add(new Scored(sid, vid, s));
    }
    // Sort descending by score
    scored.sort((a, b) -> b.score().compareTo(a.score()));

    BigDecimal totalScore =
        scored.stream().map(Scored::score).reduce(BigDecimal.ZERO, BigDecimal::add);

    UUID runId = UUID.randomUUID();
    Instant now = Instant.now();
    List<AbcAssignment> assignments = new ArrayList<>();
    BigDecimal cumulative = BigDecimal.ZERO;

    for (int i = 0; i < scored.size(); i++) {
      Scored s = scored.get(i);
      cumulative = cumulative.add(s.score());
      BigDecimal cumulativePct =
          totalScore.compareTo(BigDecimal.ZERO) == 0
              ? BigDecimal.valueOf(100)
              : cumulative
                  .divide(totalScore, 4, java.math.RoundingMode.HALF_UP)
                  .multiply(BigDecimal.valueOf(100));

      String abcClass;
      if (cumulativePct.compareTo(tA) <= 0) abcClass = "A";
      else if (cumulativePct.compareTo(tAB) <= 0) abcClass = "B";
      else abcClass = "C";

      assignments.add(
          new AbcAssignment(
              UUID.randomUUID(),
              tenantId,
              s.storeId(),
              s.variantId(),
              runId,
              abcClass,
              s.score(),
              i + 1,
              now));
    }

    AbcCompileRun run =
        new AbcCompileRun(runId, tenantId, storeId, crit, tA, tAB, assignments.size(), now);
    abcRepo.persistAbcRun(run, assignments);
    return new AbcCompileResult(run, assignments);
  }

  public List<AbcAssignment> listAbcAssignments(
      UUID tenantId, UUID storeId, String abcClass, int limit) {
    String cls = abcClass == null ? null : abcClass.toUpperCase(Locale.ROOT);
    if (cls != null && !List.of("A", "B", "C").contains(cls)) {
      throw new ApiException(400, "INVALID_ABC_CLASS", "class must be A, B, or C", List.of(), null);
    }
    return abcRepo.listAbcAssignments(tenantId, storeId, cls, limit);
  }

  public AbcAssignment getAbcAssignment(UUID tenantId, UUID storeId, UUID variantId) {
    return abcRepo
        .findAbcAssignment(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ABC_ASSIGNMENT_NOT_FOUND", "No ABC assignment for this variant"));
  }

  // ---- safety stock (Gap #8) ----

  public SafetyStockParams setSafetyStockParams(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String method,
      Integer leadTimeDays,
      BigDecimal serviceLevelPct,
      BigDecimal userDefinedPct) {

    String m = method == null ? SafetyStockParams.METHOD_MAD : method.toUpperCase(Locale.ROOT);
    if (!List.of(SafetyStockParams.METHOD_MAD, SafetyStockParams.METHOD_USER_DEFINED).contains(m)) {
      throw new ApiException(
          400,
          "INVALID_SAFETY_STOCK_METHOD",
          "method must be MAD or USER_DEFINED",
          List.of(),
          null);
    }
    if (SafetyStockParams.METHOD_USER_DEFINED.equals(m)
        && (userDefinedPct == null || userDefinedPct.signum() <= 0)) {
      throw new ApiException(
          400,
          "USER_DEFINED_PCT_REQUIRED",
          "userDefinedPct > 0 is required when method is USER_DEFINED",
          List.of(),
          null);
    }
    int ltd = leadTimeDays == null || leadTimeDays < 1 ? 7 : leadTimeDays;
    BigDecimal slp = serviceLevelPct == null ? BigDecimal.valueOf(95) : serviceLevelPct;

    var params =
        new SafetyStockParams(
            UUID.randomUUID(),
            tenantId,
            storeId,
            variantId,
            m,
            ltd,
            slp,
            userDefinedPct,
            null,
            null,
            Instant.now());
    return safetyStockRepo.upsertSafetyStockParams(params);
  }

  public SafetyStockParams getSafetyStockParams(UUID tenantId, UUID storeId, UUID variantId) {
    return safetyStockRepo
        .findSafetyStockParams(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "SAFETY_STOCK_PARAMS_NOT_FOUND", "No safety stock params for this variant"));
  }

  public List<SafetyStockParams> listSafetyStockParams(UUID tenantId, UUID storeId, int limit) {
    return safetyStockRepo.listSafetyStockParams(tenantId, storeId, limit);
  }

  /**
   * Re-compute safety_stock_qty for every (store, variant) row that has params. Returns the count
   * of rows updated. Rows with no demand history get safety_stock_qty = 0. Optional storeId/
   * variantId narrow the run to one row.
   */
  public int computeSafetyStock(UUID tenantId, UUID storeId, UUID variantId) {
    List<SafetyStockParams> targets;
    if (variantId != null && storeId != null) {
      targets =
          safetyStockRepo
              .findSafetyStockParams(tenantId, storeId, variantId)
              .map(List::of)
              .orElse(List.of());
    } else {
      targets = safetyStockRepo.listSafetyStockParamsAll(tenantId, storeId);
    }
    if (targets.isEmpty()) return 0;

    // One batched read for every target's demand history, instead of one query per row.
    var bucketsByStoreThenVariant = demandHistoryRepo.demandBucketsBatch(tenantId, targets, 30);
    Instant now = Instant.now();
    var qtyByStoreThenVariant = new java.util.HashMap<UUID, java.util.Map<UUID, BigDecimal>>();
    for (SafetyStockParams p : targets) {
      List<DemandBucket> buckets =
          bucketsByStoreThenVariant
              .getOrDefault(p.storeId(), java.util.Map.of())
              .getOrDefault(p.variantId(), List.of());
      BigDecimal qty = computeForOne(p, buckets);
      qtyByStoreThenVariant
          .computeIfAbsent(p.storeId(), k -> new java.util.HashMap<>())
          .put(p.variantId(), qty);
    }
    // One batched write for every target, instead of one connection checkout per row.
    return safetyStockRepo.updateSafetyStockQtyBatch(tenantId, qtyByStoreThenVariant, now);
  }

  /** {@code buckets} is the last 30 daily buckets (enough for meaningful MAD), oldest-first. */
  private BigDecimal computeForOne(SafetyStockParams p, List<DemandBucket> buckets) {
    if (buckets.isEmpty()) return BigDecimal.ZERO;

    int n = buckets.size();
    BigDecimal sum =
        buckets.stream().map(DemandBucket::demandQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal mean = sum.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.HALF_UP);

    if (SafetyStockParams.METHOD_USER_DEFINED.equals(p.method())) {
      // safety_stock = (mean * lead_time_days) * (userDefinedPct / 100)
      BigDecimal avgOverLead = mean.multiply(BigDecimal.valueOf(p.leadTimeDays()));
      BigDecimal pct =
          p.userDefinedPct().divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
      return avgOverLead.multiply(pct).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    // MAD = mean of |demand_i − mean|
    BigDecimal madSum =
        buckets.stream()
            .map(b -> b.demandQty().subtract(mean).abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal mad = madSum.divide(BigDecimal.valueOf(n), 6, java.math.RoundingMode.HALF_UP);

    // z-score lookup for common service levels; linear interpolation not needed — standard table
    double sl = p.serviceLevelPct().doubleValue();
    double z;
    if (sl >= 99.0) z = 2.326;
    else if (sl >= 98.0) z = 2.054;
    else if (sl >= 97.0) z = 1.881;
    else if (sl >= 95.0) z = 1.645;
    else if (sl >= 90.0) z = 1.282;
    else if (sl >= 85.0) z = 1.036;
    else z = 0.842;

    // safety_stock = z * MAD * sqrt(lead_time_days)
    double sqrtLt = Math.sqrt(p.leadTimeDays());
    BigDecimal ss =
        mad.multiply(BigDecimal.valueOf(z))
            .multiply(BigDecimal.valueOf(sqrtLt))
            .setScale(3, java.math.RoundingMode.HALF_UP);
    return ss.max(BigDecimal.ZERO);
  }

  // ---- sweeper support ----
  public List<com.shelfj.inventory.repo.InventoryRepository.ReservationRef>
      expiredReservationsWithTenant(int limit) {
    return repo.expiredHeldReservationsWithTenant(limit);
  }

  static UUID parseUuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  public PhysicalInventory createPhysicalInventory(UUID tenantId, UUID storeId, String notes) {
    UUID id = UUID.randomUUID();
    var pi =
        new PhysicalInventory(
            id, tenantId, storeId, PhysicalInventory.OPEN, notes, Instant.now(), null);
    var event =
        new OutboxRow(
            "PhysicalInventoryCreated",
            "shelfj.inventory.physical-inventory-created",
            tenantId,
            id,
            Events.physicalInventoryCreated(tenantId, id, storeId));
    return physicalInventoryRepo.createPhysicalInventory(pi, event);
  }

  public PhysicalInventory getPhysicalInventory(UUID tenantId, UUID id) {
    return physicalInventoryRepo
        .findPhysicalInventory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("PI_NOT_FOUND", "Physical inventory not found"));
  }

  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, String storeId) {
    UUID storeUuid = storeId != null ? parseUuid(storeId, "storeId") : null;
    return physicalInventoryRepo.listPhysicalInventories(tenantId, storeUuid);
  }

  public PhysicalInventoryTag addTag(
      UUID tenantId, UUID piId, UUID variantId, UUID zoneId, BigDecimal systemQty) {
    getPhysicalInventory(tenantId, piId);
    var tag =
        new PhysicalInventoryTag(
            UUID.randomUUID(),
            tenantId,
            piId,
            variantId,
            zoneId,
            systemQty,
            null,
            null,
            PhysicalInventoryTag.OPEN,
            null);
    return physicalInventoryRepo.addTag(tag);
  }

  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID piId, UUID tagId, BigDecimal countedQty) {
    return physicalInventoryRepo.countTag(tenantId, piId, tagId, countedQty);
  }

  public PhysicalInventory completePhysicalInventory(UUID tenantId, UUID piId) {
    getPhysicalInventory(tenantId, piId);
    var event =
        new OutboxRow(
            "PhysicalInventoryCompleted",
            "shelfj.inventory.physical-inventory-completed",
            tenantId,
            piId,
            Events.physicalInventoryCompleted(tenantId, piId));
    return physicalInventoryRepo.completePhysicalInventory(tenantId, piId, event);
  }

  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID piId) {
    return physicalInventoryRepo.listTags(tenantId, piId);
  }

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────────

  public ReorderPointPlan upsertRopPlan(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      int leadTimeDays,
      java.math.BigDecimal orderingCost,
      java.math.BigDecimal holdingCostPct,
      java.math.BigDecimal unitCost) {
    var plan =
        new ReorderPointPlan(
            null,
            tenantId,
            storeId,
            variantId,
            leadTimeDays,
            orderingCost,
            holdingCostPct,
            unitCost,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var event =
        new OutboxRow(
            "RopPlanUpdated",
            "shelfj.inventory.rop-plan-updated",
            tenantId,
            variantId,
            Events.ropPlanUpdated(tenantId, storeId, variantId));
    return ropRepo.upsertRopPlan(plan, event);
  }

  public ReorderPointPlan getRopPlan(UUID tenantId, UUID storeId, UUID variantId) {
    return ropRepo
        .findRopPlan(tenantId, storeId, variantId)
        .orElseThrow(() -> ApiException.notFound("ROP_NOT_FOUND", "ROP plan not found"));
  }

  public List<ReorderPointPlan> listRopPlans(UUID tenantId, UUID storeId) {
    return ropRepo.listRopPlans(tenantId, storeId);
  }

  public int computeRopPlans(UUID tenantId, UUID storeId) {
    return ropRepo.computeRopPlans(tenantId, storeId);
  }

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────────

  private static final java.util.Set<String> KANBAN_TYPES =
      java.util.Set.of("SUPPLIER", "INTER_ORG", "INTRA_ORG", "PRODUCTION");

  public KanbanCard createKanbanCard(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String kanbanType,
      java.math.BigDecimal reorderQty,
      UUID sourceStoreId,
      String supplierRef,
      String notes) {
    if (!KANBAN_TYPES.contains(kanbanType)) {
      throw ApiException.badRequest(
          "INVALID_KANBAN_TYPE", "kanban type must be one of " + KANBAN_TYPES);
    }
    UUID cardId = UUID.randomUUID();
    KanbanCard card =
        new KanbanCard(
            cardId,
            tenantId,
            storeId,
            variantId,
            kanbanType,
            KanbanCard.EMPTY,
            reorderQty,
            sourceStoreId,
            supplierRef,
            notes,
            null,
            null,
            null,
            Instant.now(),
            null,
            null);
    var event =
        new OutboxRow(
            "KanbanCreated",
            "shelfj.inventory.kanban-created",
            tenantId,
            cardId,
            Events.kanbanCreated(tenantId, cardId, storeId, variantId, kanbanType));
    return kanbanRepo.createKanbanCard(card, event);
  }

  public KanbanCard triggerKanbanCard(UUID tenantId, UUID cardId, String notes) {
    KanbanCard card =
        kanbanRepo
            .findKanbanCard(tenantId, cardId)
            .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
    var event =
        new OutboxRow(
            "KanbanTriggered",
            "shelfj.inventory.kanban-triggered",
            tenantId,
            cardId,
            Events.kanbanTriggered(tenantId, cardId, card.storeId(), card.variantId()));
    return kanbanRepo.triggerKanbanCard(tenantId, cardId, notes, event);
  }

  public KanbanCard replenishKanbanCard(UUID tenantId, UUID cardId) {
    KanbanCard card =
        kanbanRepo
            .findKanbanCard(tenantId, cardId)
            .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
    var event =
        new OutboxRow(
            "KanbanReplenished",
            "shelfj.inventory.kanban-replenished",
            tenantId,
            cardId,
            Events.kanbanReplenished(tenantId, cardId, card.storeId(), card.variantId()));
    return kanbanRepo.replenishKanbanCard(tenantId, cardId, event);
  }

  public KanbanCard getKanbanCard(UUID tenantId, UUID cardId) {
    return kanbanRepo
        .findKanbanCard(tenantId, cardId)
        .orElseThrow(() -> ApiException.notFound("KANBAN_NOT_FOUND", "kanban card not found"));
  }

  public List<KanbanCard> listKanbanCards(UUID tenantId, UUID storeId, String status) {
    return kanbanRepo.listKanbanCards(tenantId, storeId, status);
  }

  // ── Gap #17: Costing Methods ────────────────────────────────────────────────

  public CostingMethod upsertCostingMethod(
      UUID tenantId, UUID storeId, UUID variantId, String method) {
    if (!"FIFO".equals(method) && !"AVERAGE".equals(method)) {
      throw ApiException.badRequest("INVALID_COSTING_METHOD", "method must be FIFO or AVERAGE");
    }
    var event =
        new OutboxRow(
            "CostingMethodUpdated",
            "shelfj.inventory.costing-method-updated",
            tenantId,
            variantId,
            Events.costingMethodUpdated(tenantId, storeId, variantId, method));
    return costingRepo.upsertCostingMethod(tenantId, storeId, variantId, method, event);
  }

  public CostingMethod getCostingMethod(UUID tenantId, UUID storeId, UUID variantId) {
    return costingRepo
        .findCostingMethod(tenantId, storeId, variantId)
        .orElseThrow(
            () -> ApiException.notFound("COSTING_METHOD_NOT_FOUND", "costing method not found"));
  }

  public List<CostingMethod> listCostingMethods(UUID tenantId, UUID storeId) {
    return costingRepo.listCostingMethods(tenantId, storeId);
  }

  public AccountingPeriod openPeriod(
      UUID tenantId, UUID storeId, String periodName, String periodDate) {
    LocalDate date = LocalDate.parse(periodDate);
    var event =
        new OutboxRow(
            "AccountingPeriodOpened",
            "shelfj.inventory.accounting-period-opened",
            tenantId,
            storeId,
            Events.accountingPeriodOpened(tenantId, storeId, periodName, periodDate));
    return costingRepo.openPeriod(tenantId, storeId, periodName, date, event);
  }

  public AccountingPeriod closePeriod(UUID tenantId, UUID periodId) {
    var event =
        new OutboxRow(
            "AccountingPeriodClosed",
            "shelfj.inventory.accounting-period-closed",
            tenantId,
            periodId,
            Events.accountingPeriodClosed(tenantId, periodId));
    return costingRepo.closePeriod(tenantId, periodId, event);
  }

  public AccountingPeriod getPeriod(UUID tenantId, UUID periodId) {
    return costingRepo
        .findPeriod(tenantId, periodId)
        .orElseThrow(
            () -> ApiException.notFound("PERIOD_NOT_FOUND", "accounting period not found"));
  }

  public List<AccountingPeriod> listPeriods(UUID tenantId, UUID storeId) {
    return costingRepo.listPeriods(tenantId, storeId);
  }

  // ── Tier-1 Gap #21: Transaction reason codes ─────────────────────────────

  public ReasonCode createReasonCode(UUID tenantId, String code, String description) {
    return refData.insertReasonCode(tenantId, code.toUpperCase(Locale.ROOT), description);
  }

  public List<ReasonCode> listReasonCodes(UUID tenantId) {
    return refData.listReasonCodes(tenantId);
  }

  public ReasonCode setReasonCodeActive(UUID tenantId, UUID id, boolean active) {
    return refData.setReasonCodeActive(tenantId, id, active);
  }

  // ── Tier-1 Gap #22: Transaction source types ──────────────────────────────

  public TransactionSourceType createSourceType(UUID tenantId, String code, String description) {
    return refData.insertSourceType(tenantId, code.toUpperCase(Locale.ROOT), description);
  }

  public List<TransactionSourceType> listSourceTypes(UUID tenantId) {
    return refData.listSourceTypes(tenantId);
  }

  public TransactionSourceType setSourceTypeActive(UUID tenantId, UUID id, boolean active) {
    return refData.setSourceTypeActive(tenantId, id, active);
  }

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  public record LotSplitResult(Batch newBatch, LotAction action) {}

  public LotSplitResult splitLot(
      UUID tenantId, UUID sourceBatchId, BigDecimal qty, String batchNo, String notes) {
    Batch source =
        repo.getBatch(tenantId, sourceBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Source batch not found"));
    if (source.remainingQty().compareTo(qty) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_QTY", "Split qty exceeds remaining qty on source batch");
    }
    String newBatchNo =
        batchNo != null
            ? batchNo
            : source.batchNo() + "-SPLIT-" + UUID.randomUUID().toString().substring(0, 8);
    UUID newBatchId = UUID.randomUUID();
    Batch splitBatch =
        new Batch(
            newBatchId,
            tenantId,
            source.storeId(),
            source.variantId(),
            newBatchNo,
            qty,
            qty,
            source.costPrice(),
            source.expiryDate(),
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            source.grade(),
            source.zoneId());
    OutboxRow splitEvent =
        new OutboxRow(
            "LotSplit",
            "shelfj.inventory.lot-split",
            tenantId,
            sourceBatchId,
            Events.lotSplit(tenantId, sourceBatchId, newBatchId, qty));
    Batch newBatch = repo.receive(splitBatch, "LOT_SPLIT", sourceBatchId, splitEvent, null);
    LotAction action =
        lotActionRepo.insertLotAction(
            tenantId, LotAction.SPLIT, sourceBatchId, newBatch.id(), qty, notes);
    return new LotSplitResult(newBatch, action);
  }

  public record LotMergeResult(Batch targetBatch, LotAction action) {}

  public LotMergeResult mergeLot(
      UUID tenantId, UUID sourceBatchId, UUID targetBatchId, BigDecimal qty, String notes) {
    Batch source =
        repo.getBatch(tenantId, sourceBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Source batch not found"));
    Batch target =
        repo.getBatch(tenantId, targetBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Target batch not found"));
    if (source.remainingQty().compareTo(qty) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_QTY", "Merge qty exceeds remaining qty on source batch");
    }
    OutboxRow mergeEvent =
        new OutboxRow(
            "LotMerge",
            "shelfj.inventory.lot-merge",
            tenantId,
            sourceBatchId,
            Events.lotMerge(tenantId, sourceBatchId, targetBatchId, qty));
    OutboxRow addEvent =
        new OutboxRow(
            "LotMergeIn",
            "shelfj.inventory.lot-merge-in",
            tenantId,
            targetBatchId,
            Events.lotMerge(tenantId, sourceBatchId, targetBatchId, qty));
    // Deduct from source and add to target atomically — see mergeLotAdjust's Javadoc.
    repo.mergeLotAdjust(
        tenantId,
        source.storeId(),
        source.variantId(),
        mergeEvent,
        target.storeId(),
        target.variantId(),
        addEvent,
        qty);
    Batch updated =
        repo.getBatch(tenantId, targetBatchId)
            .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "Target batch not found"));
    LotAction action =
        lotActionRepo.insertLotAction(
            tenantId, LotAction.MERGE, sourceBatchId, targetBatchId, qty, notes);
    return new LotMergeResult(updated, action);
  }

  public List<LotAction> listLotActions(UUID tenantId, UUID batchId) {
    return lotActionRepo.listLotActions(tenantId, batchId);
  }

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  public List<Batch> listExpiringBatches(UUID tenantId, UUID storeId, int withinDays) {
    if (withinDays < 1 || withinDays > 3650) {
      throw ApiException.badRequest("INVALID_DAYS", "withinDays must be 1–3650");
    }
    return repo.listExpiringBatches(tenantId, storeId, withinDays);
  }

  // ── Tier-1 Gap #25: Grade control ─────────────────────────────────────────

  public Batch updateBatchGrade(UUID tenantId, UUID batchId, String grade) {
    if (grade == null || grade.isBlank()) {
      throw ApiException.badRequest("INVALID_GRADE", "grade must not be blank");
    }
    return repo.updateBatchGrade(tenantId, batchId, grade.toUpperCase(Locale.ROOT));
  }

  // ── Tier-1 Gap #26: Lot UOM conversions ──────────────────────────────────

  public LotUomConversion upsertLotUomConversion(
      UUID tenantId, UUID batchId, String fromUom, String toUom, BigDecimal factor, String notes) {
    if (factor.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_FACTOR", "UOM conversion factor must be positive");
    }
    return planningConfig.upsertLotUomConversion(tenantId, batchId, fromUom, toUom, factor, notes);
  }

  public List<LotUomConversion> listLotUomConversions(UUID tenantId, UUID batchId) {
    return planningConfig.listLotUomConversions(tenantId, batchId);
  }

  // ── Tier-1 Gap #27: PAR levels ────────────────────────────────────────────

  public ParLevelConfig upsertParLevel(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal parQty,
      String uom,
      String reviewCycle) {
    if (parQty.compareTo(BigDecimal.ZERO) <= 0) {
      throw ApiException.badRequest("INVALID_PAR_QTY", "parQty must be positive");
    }
    String cycle =
        reviewCycle == null ? ParLevelConfig.DAILY : reviewCycle.toUpperCase(Locale.ROOT);
    if (!Set.of(ParLevelConfig.DAILY, ParLevelConfig.WEEKLY, ParLevelConfig.MONTHLY)
        .contains(cycle)) {
      throw ApiException.badRequest(
          "INVALID_REVIEW_CYCLE", "reviewCycle must be DAILY, WEEKLY, or MONTHLY");
    }
    return planningConfig.upsertParLevel(tenantId, storeId, variantId, parQty, uom, cycle);
  }

  public List<ParLevelConfig> listParLevels(UUID tenantId, UUID storeId) {
    return planningConfig.listParLevels(tenantId, storeId);
  }

  public ParLevelConfig getParLevel(UUID tenantId, UUID storeId, UUID variantId) {
    return planningConfig
        .findParLevel(tenantId, storeId, variantId)
        .orElseThrow(() -> ApiException.notFound("PAR_LEVEL_NOT_FOUND", "No PAR level configured"));
  }

  // ── Tier-1 Gap #28: Order modifiers ──────────────────────────────────────

  public ReorderPointPlan updateRopOrderModifiers(
      UUID tenantId, UUID ropId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
    return ropRepo.updateRopOrderModifiers(tenantId, ropId, min, max, lotMult);
  }

  public KanbanCard updateKanbanOrderModifiers(
      UUID tenantId, UUID cardId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
    return kanbanRepo.updateKanbanOrderModifiers(tenantId, cardId, min, max, lotMult);
  }

  // ── Tier-1 Gap #29: Batch (bulk) reservations ─────────────────────────────

  public record BulkReserveResult(int succeeded, int failed, List<Reservation> results) {}

  public BulkReserveResult bulkReserve(
      UUID tenantId, List<com.shelfj.inventory.dto.Dtos.ReserveRequest> requests) {
    List<InventoryRepository.ReserveBatchItem> items = new ArrayList<>(requests.size());
    int parseFailed = 0;
    for (var req : requests) {
      try {
        UUID storeId = UUID.fromString(req.storeId());
        UUID variantId = UUID.fromString(req.variantId());
        UUID orderId = req.orderId() != null ? UUID.fromString(req.orderId()) : null;
        long ttl = req.ttlSeconds() == null ? config.reservationTtlSeconds() : req.ttlSeconds();
        UUID id = UUID.randomUUID();
        var reservation =
            new Reservation(
                id,
                tenantId,
                storeId,
                variantId,
                req.qty(),
                orderId,
                Reservation.HELD,
                Instant.now().plusSeconds(ttl),
                Instant.now());
        var event =
            new OutboxRow(
                "StockReserved",
                "shelfj.inventory.stock-reserved",
                tenantId,
                id,
                Events.stockReserved(tenantId, storeId, variantId, id, req.qty()));
        items.add(new InventoryRepository.ReserveBatchItem(reservation, event, null));
      } catch (Exception e) {
        parseFailed++;
      }
    }

    List<Reservation> succeeded = new ArrayList<>();
    int dbFailed = 0;
    if (!items.isEmpty()) {
      for (var outcome : repo.reserveBatch(items)) {
        if (outcome.succeeded()) {
          succeeded.add(outcome.reservation());
        } else {
          dbFailed++;
        }
      }
    }
    return new BulkReserveResult(succeeded.size(), parseFailed + dbFailed, succeeded);
  }

  // ── Tier-1 Gap #30: Purge transaction history ─────────────────────────────

  public int purgeMovementsBefore(UUID tenantId, Instant before) {
    Instant cutoff = Instant.now().minusSeconds(90L * 24 * 3600);
    if (before.isAfter(cutoff)) {
      throw ApiException.badRequest(
          "PURGE_TOO_RECENT", "Cannot purge movements less than 90 days old");
    }
    return movementArchiveRepo.purgeMovementsBefore(tenantId, before);
  }

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  public ZoneGlMapping upsertZoneGlMapping(
      UUID tenantId, UUID storeId, UUID zoneId, String nominalCode, String description) {
    return refData.upsertZoneGlMapping(tenantId, storeId, zoneId, nominalCode, description);
  }

  public List<ZoneGlMapping> listZoneGlMappings(UUID tenantId, UUID storeId) {
    return refData.listZoneGlMappings(tenantId, storeId);
  }

  // ── Picking Rules (Gap #38) ──────────────────────────────────────────────

  public PickingRule createPickingRule(
      UUID tenantId, com.shelfj.inventory.dto.Dtos.CreatePickingRuleRequest req) {
    String strategy = req.strategy().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("FIFO", "FEFO", "LIFO", "FEFO_GRADE", "ZONE_PRIORITY")
        .contains(strategy)) {
      throw new ApiException(
          400,
          "INVALID_STRATEGY",
          "strategy must be FIFO, FEFO, LIFO, FEFO_GRADE, or ZONE_PRIORITY",
          List.of(),
          null);
    }
    return pickingRuleRepo.createPickingRule(
        tenantId, req.name().trim(), strategy, req.gradePreference());
  }

  public PickingRule getPickingRule(UUID tenantId, UUID id) {
    return pickingRuleRepo
        .findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  public List<PickingRule> listPickingRules(UUID tenantId, int limit) {
    return pickingRuleRepo.listPickingRules(tenantId, limit);
  }

  public PickingRule deactivatePickingRule(UUID tenantId, UUID id) {
    getPickingRule(tenantId, id);
    return pickingRuleRepo.deactivatePickingRule(tenantId, id);
  }

  public List<PickingRuleZonePriority> setZonePriorities(
      UUID tenantId, UUID ruleId, com.shelfj.inventory.dto.Dtos.SetZonePrioritiesRequest req) {
    getPickingRule(tenantId, ruleId);
    List<PickingRuleZonePriority> items =
        req.zonePriorities().stream()
            .map(
                e ->
                    new PickingRuleZonePriority(
                        null, tenantId, ruleId, UUID.fromString(e.zoneId()), e.priority()))
            .toList();
    pickingRuleRepo.replaceZonePriorities(tenantId, ruleId, items);
    return pickingRuleRepo.listZonePriorities(tenantId, ruleId);
  }

  public List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
    getPickingRule(tenantId, ruleId);
    return pickingRuleRepo.listZonePriorities(tenantId, ruleId);
  }

  public PickingRuleAssignment createPickingRuleAssignment(
      UUID tenantId, com.shelfj.inventory.dto.Dtos.CreatePickingRuleAssignmentRequest req) {
    UUID ruleId = UUID.fromString(req.ruleId());
    getPickingRule(tenantId, ruleId);
    String scopeType = req.scopeType().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("GLOBAL", "STORE", "PRODUCT").contains(scopeType)) {
      throw new ApiException(
          400,
          "INVALID_SCOPE_TYPE",
          "scopeType must be GLOBAL, STORE, or PRODUCT",
          List.of(),
          null);
    }
    UUID scopeId =
        (req.scopeId() != null && !req.scopeId().isBlank()) ? UUID.fromString(req.scopeId()) : null;
    if (!"GLOBAL".equals(scopeType) && scopeId == null) {
      throw new ApiException(
          400,
          "SCOPE_ID_REQUIRED",
          "scopeId is required for scope type " + scopeType,
          List.of(),
          null);
    }
    return pickingRuleRepo.createPickingRuleAssignment(tenantId, ruleId, scopeType, scopeId);
  }

  public List<PickingRuleAssignment> listPickingRuleAssignments(UUID tenantId, int limit) {
    return pickingRuleRepo.listPickingRuleAssignments(tenantId, limit);
  }

  public void deletePickingRuleAssignment(UUID tenantId, UUID id) {
    if (!pickingRuleRepo.deletePickingRuleAssignment(tenantId, id)) {
      throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Picking rule assignment not found");
    }
  }

  public com.shelfj.inventory.dto.Dtos.PickingRuleResolveResponse resolvePickingRule(
      UUID tenantId, UUID storeId, UUID variantId) {
    var rule = pickingRuleRepo.resolvePickingRule(tenantId, storeId, variantId).orElse(null);
    String strategy = rule != null ? rule.strategy() : PickingRule.FEFO;
    String gradePreference = rule != null ? rule.gradePreference() : null;
    List<UUID> zonePriorityOrder =
        (rule != null && PickingRule.ZONE_PRIORITY.equals(strategy))
            ? pickingRuleRepo.listZonePriorities(tenantId, rule.id()).stream()
                .map(PickingRuleZonePriority::zoneId)
                .toList()
            : null;
    var batches =
        repo.previewPickOrder(
            tenantId, storeId, variantId, strategy, gradePreference, zonePriorityOrder);
    var pickOrder =
        batches.stream()
            .map(
                b ->
                    new com.shelfj.inventory.dto.Dtos.PickingRuleResolveResponse.PickBatchPreview(
                        b.id().toString(),
                        b.batchNo(),
                        null,
                        b.remainingQty(),
                        b.expiryDate() != null ? b.expiryDate().toString() : null,
                        b.grade(),
                        b.createdAt().toString()))
            .toList();
    return new com.shelfj.inventory.dto.Dtos.PickingRuleResolveResponse(
        rule != null ? rule.id().toString() : null,
        rule != null ? rule.name() : null,
        strategy,
        gradePreference,
        pickOrder);
  }
}
