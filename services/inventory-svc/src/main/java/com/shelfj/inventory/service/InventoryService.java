package com.shelfj.inventory.service;

import com.shelfj.inventory.config.ServiceConfig;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.repo.InventoryRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
            Batch.STATUS_ACTIVE);
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

  public List<Batch> listBatches(UUID tenantId, UUID storeId, UUID variantId, int limit) {
    return repo.listBatches(tenantId, storeId, variantId, limit);
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

  public Threshold setThreshold(UUID tenantId, UUID storeId, UUID variantId, BigDecimal threshold) {
    return repo.upsertThreshold(
        new Threshold(UUID.randomUUID(), tenantId, storeId, variantId, threshold));
  }

  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    return repo.listThresholds(tenantId, storeId);
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
