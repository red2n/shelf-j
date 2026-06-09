package com.shelfj.inventory.service;

import com.shelfj.inventory.config.ServiceConfig;
import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.LotGenealogyLink;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.PhysicalInventory;
import com.shelfj.inventory.domain.Domain.PhysicalInventoryTag;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.inventory.domain.Domain.SerialMovement;
import com.shelfj.inventory.domain.Domain.SerialNumber;
import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.domain.Domain.TransferOrder;
import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.inventory.repo.InventoryRepository;
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
import java.util.UUID;

/** Stock business logic. All mutations emit a stock event via the outbox (golden rule #6). */
@ApplicationScoped
public class InventoryService {

  @Inject ServiceConfig config;
  @Inject InventoryRepository repo;

  // ---- receive (also the path the GoodsReceived consumer uses) ----
  public Batch receive(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      String refType,
      UUID refId) {
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
            null);
    var event =
        new OutboxRow(
            "StockReceived",
            "shelfj.inventory.stock-received",
            tenantId,
            batchId,
            Events.stockReceived(tenantId, storeId, variantId, batchId, qty));
    return repo.receive(batch, refType, refId, event);
  }

  // ---- adjust ----
  public void adjust(UUID tenantId, UUID storeId, UUID variantId, BigDecimal delta, String reason) {
    var event =
        new OutboxRow(
            "StockAdjusted",
            "shelfj.inventory.stock-adjusted",
            tenantId,
            variantId,
            Events.stockAdjusted(tenantId, storeId, variantId, delta));
    repo.adjust(tenantId, storeId, variantId, delta, reason, event);
  }

  // ---- reserve ----
  public Reservation reserve(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId, Long ttlSeconds) {
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
    return repo.reserve(reservation, event);
  }

  // ---- consume (FIFO deduct) ----
  public void consume(UUID tenantId, UUID reservationId) {
    var event =
        new OutboxRow(
            "StockDeducted",
            "shelfj.inventory.stock-deducted",
            tenantId,
            reservationId,
            Events.reservationEvent("StockDeducted", tenantId, reservationId));
    repo.consume(tenantId, reservationId, event);
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
    return repo.listMovements(tenantId, storeId, variantId, type, limit);
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
    return repo.upsertThreshold(
        new Threshold(UUID.randomUUID(), tenantId, storeId, variantId, threshold, maxQty));
  }

  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    return repo.listThresholds(tenantId, storeId);
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

    List<Threshold> thresholds = repo.listThresholds(tenantId, storeId);
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
      repo.insertSuggestionIfAbsent(sugg, event).ifPresent(created::add);
    }
    return created;
  }

  public List<Suggestion> listSuggestions(UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listSuggestions(tenantId, storeId, status, limit);
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
    return repo.resolveSuggestion(tenantId, suggId, newStatus, event)
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
    return repo.registerSerials(domainSerials, event);
  }

  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    return repo.listSerials(tenantId, storeId, variantId, status, limit);
  }

  public SerialNumber getSerial(UUID tenantId, UUID serialId) {
    return repo.findSerial(tenantId, serialId)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  public SerialNumber lookupSerialByNo(UUID tenantId, String serialNo) {
    return repo.findSerialByNo(tenantId, serialNo)
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
    return repo.updateSerialStatus(tenantId, serialId, newStatus, event)
        .orElseThrow(() -> ApiException.notFound("SERIAL_NOT_FOUND", "No such serial number"));
  }

  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return repo.listSerialHistory(tenantId, serialId);
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
    return repo.aggregateDemand(tenantId, storeId, bt, since);
  }

  public List<DemandBucket> listDemandHistory(
      UUID tenantId, UUID storeId, UUID variantId, String bucketType, int limit) {
    String bt = bucketType == null ? null : bucketType.toUpperCase(Locale.ROOT);
    return repo.listDemandHistory(tenantId, storeId, variantId, bt, limit);
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
    List<AbcAssignment> assignments = repo.listAbcAssignments(tenantId, storeId, null, 1000);
    List<CycleCountLine> lines = new ArrayList<>();
    for (AbcAssignment a : assignments) {
      if (!requestedClasses.contains(a.abcClass())) continue;
      BigDecimal onHand = repo.onHandQty(tenantId, storeId, a.variantId());
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

    repo.createCycleCountHeader(header, lines);
    return new CycleCountWithLines(header, lines);
  }

  public List<CycleCountWithLines> listCycleCounts(
      UUID tenantId, UUID storeId, String status, int limit) {
    return repo.listCycleCountHeaders(tenantId, storeId, status, limit).stream()
        .map(h -> new CycleCountWithLines(h, repo.listCycleCountLines(h.id())))
        .toList();
  }

  public CycleCountWithLines getCycleCount(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        repo.findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    return new CycleCountWithLines(header, repo.listCycleCountLines(headerId));
  }

  /** Record the physically counted qty for one line; computes variance. */
  public CycleCountLine enterCount(
      UUID tenantId, UUID headerId, UUID lineId, BigDecimal countedQty) {
    if (countedQty.signum() < 0) {
      throw new ApiException(400, "INVALID_COUNT", "countedQty must be >= 0", List.of(), null);
    }
    // Verify line belongs to this header + tenant
    CycleCountLine existing =
        repo.findCycleCountLine(tenantId, lineId)
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
    repo.findCycleCountHeader(tenantId, headerId)
        .ifPresent(
            h -> {
              if (CycleCountHeader.OPEN.equals(h.status())) {
                repo.updateHeaderStatus(tenantId, headerId, CycleCountHeader.IN_PROGRESS);
              }
            });

    return repo.enterCount(tenantId, lineId, countedQty, variance, variancePct)
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
        repo.findCycleCountHeader(tenantId, headerId)
            .orElseThrow(
                () -> ApiException.notFound("CYCLE_COUNT_NOT_FOUND", "No such cycle count"));
    if (CycleCountHeader.ADJUSTED.equals(header.status())
        || CycleCountHeader.CLOSED.equals(header.status())) {
      throw new ApiException(
          422, "CYCLE_COUNT_CLOSED", "Cycle count is already " + header.status(), List.of(), null);
    }

    List<CycleCountLine> lines = repo.listCycleCountLines(headerId);
    List<UUID> toApprove = new ArrayList<>();
    List<UUID> toFlag = new ArrayList<>();
    for (CycleCountLine l : lines) {
      if (!CycleCountLine.COUNTED.equals(l.status())) continue;
      BigDecimal absPct = l.variancePct() == null ? BigDecimal.ZERO : l.variancePct().abs();
      if (absPct.compareTo(header.tolerancePct()) <= 0) toApprove.add(l.id());
      else toFlag.add(l.id());
    }
    repo.bulkUpdateLineStatus(headerId, toApprove, CycleCountLine.APPROVED);
    repo.bulkUpdateLineStatus(headerId, toFlag, CycleCountLine.REJECTED);

    String newHeaderStatus =
        toFlag.isEmpty() ? CycleCountHeader.IN_PROGRESS : CycleCountHeader.PENDING_APPROVAL;
    repo.updateHeaderStatus(tenantId, headerId, newHeaderStatus);
    return new ApproveResult(toApprove.size(), toFlag.size());
  }

  /** Apply stock adjustments for all APPROVED lines, then close the count header. */
  public int adjustCycleCount(UUID tenantId, UUID headerId) {
    CycleCountHeader header =
        repo.findCycleCountHeader(tenantId, headerId)
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
    int adjusted = repo.applyAdjustments(tenantId, headerId, event);
    repo.updateHeaderStatus(tenantId, headerId, CycleCountHeader.ADJUSTED);
    return adjusted;
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
    return repo.createLotLink(link);
  }

  public List<LotGenealogyLink> findAncestors(UUID tenantId, UUID batchId) {
    return repo.findAncestors(tenantId, batchId);
  }

  public List<LotGenealogyLink> findDescendants(UUID tenantId, UUID batchId) {
    return repo.findDescendants(tenantId, batchId);
  }

  public List<LotGenealogyLink> findDirectLinks(UUID tenantId, UUID batchId) {
    return repo.findDirectLinks(tenantId, batchId);
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

    List<Object[]> raw = repo.abcScoringData(tenantId, storeId);
    if (raw.isEmpty()) {
      UUID runId = UUID.randomUUID();
      AbcCompileRun emptyRun =
          new AbcCompileRun(runId, tenantId, storeId, crit, tA, tAB, 0, Instant.now());
      repo.persistAbcRun(emptyRun, List.of());
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
    repo.persistAbcRun(run, assignments);
    return new AbcCompileResult(run, assignments);
  }

  public List<AbcAssignment> listAbcAssignments(
      UUID tenantId, UUID storeId, String abcClass, int limit) {
    String cls = abcClass == null ? null : abcClass.toUpperCase(Locale.ROOT);
    if (cls != null && !List.of("A", "B", "C").contains(cls)) {
      throw new ApiException(400, "INVALID_ABC_CLASS", "class must be A, B, or C", List.of(), null);
    }
    return repo.listAbcAssignments(tenantId, storeId, cls, limit);
  }

  public AbcAssignment getAbcAssignment(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.findAbcAssignment(tenantId, storeId, variantId)
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
    return repo.upsertSafetyStockParams(params);
  }

  public SafetyStockParams getSafetyStockParams(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.findSafetyStockParams(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "SAFETY_STOCK_PARAMS_NOT_FOUND", "No safety stock params for this variant"));
  }

  public List<SafetyStockParams> listSafetyStockParams(UUID tenantId, UUID storeId, int limit) {
    return repo.listSafetyStockParams(tenantId, storeId, limit);
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
          repo.findSafetyStockParams(tenantId, storeId, variantId).map(List::of).orElse(List.of());
    } else {
      targets = repo.listSafetyStockParamsAll(tenantId, storeId);
    }
    int updated = 0;
    Instant now = Instant.now();
    for (SafetyStockParams p : targets) {
      BigDecimal qty = computeForOne(p);
      repo.updateSafetyStockQty(p.tenantId(), p.storeId(), p.variantId(), qty, now);
      updated++;
    }
    return updated;
  }

  private BigDecimal computeForOne(SafetyStockParams p) {
    // Fetch last 30 daily buckets (enough for meaningful MAD)
    List<DemandBucket> buckets =
        repo.demandBucketsForCompute(p.tenantId(), p.storeId(), p.variantId(), 30);
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
  public List<UUID> expiredReservations(int limit) {
    return repo.expiredHeldReservations(limit);
  }

  public UUID tenantOfReservation(UUID reservationId) {
    return repo.tenantOfReservation(reservationId);
  }

  static UUID parseUuid(String s, String field) {
    try {
      return UUID.fromString(s);
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
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
    return repo.createPhysicalInventory(pi, event);
  }

  public PhysicalInventory getPhysicalInventory(UUID tenantId, UUID id) {
    return repo.findPhysicalInventory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("PI_NOT_FOUND", "Physical inventory not found"));
  }

  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, String storeId) {
    UUID storeUuid = storeId != null ? parseUuid(storeId, "storeId") : null;
    return repo.listPhysicalInventories(tenantId, storeUuid);
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
    return repo.addTag(tag);
  }

  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID piId, UUID tagId, BigDecimal countedQty) {
    return repo.countTag(tenantId, piId, tagId, countedQty);
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
    return repo.completePhysicalInventory(tenantId, piId, event);
  }

  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID piId) {
    return repo.listTags(tenantId, piId);
  }
}
