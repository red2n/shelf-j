package com.shelfj.inventory.service;

import com.shelfj.inventory.config.ServiceConfig;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.inventory.domain.Domain.Threshold;
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

  // ---- demand history (Gap #7) ----

  public int aggregateDemand(UUID tenantId, UUID storeId, String bucketType, LocalDate since) {
    String bt = bucketType == null ? DemandBucket.BUCKET_WEEK : bucketType.toUpperCase();
    if (!List.of(DemandBucket.BUCKET_DAY, DemandBucket.BUCKET_WEEK, DemandBucket.BUCKET_MONTH)
        .contains(bt)) {
      throw new ApiException(
          400, "INVALID_BUCKET_TYPE", "bucketType must be DAY, WEEK, or MONTH", List.of(), null);
    }
    return repo.aggregateDemand(tenantId, storeId, bt, since);
  }

  public List<DemandBucket> listDemandHistory(
      UUID tenantId, UUID storeId, UUID variantId, String bucketType, int limit) {
    String bt = bucketType == null ? null : bucketType.toUpperCase();
    return repo.listDemandHistory(tenantId, storeId, variantId, bt, limit);
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
