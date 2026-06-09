package com.shelfj.inventory.service;

import com.shelfj.inventory.config.ServiceConfig;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
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
}
