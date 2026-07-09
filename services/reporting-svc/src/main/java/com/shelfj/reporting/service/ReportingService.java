package com.shelfj.reporting.service;

import com.shelfj.reporting.domain.Domain.InventoryProjection;
import com.shelfj.reporting.domain.Domain.MovementStat;
import com.shelfj.reporting.domain.Domain.OpenSupplyLine;
import com.shelfj.reporting.domain.Domain.SalesDayStat;
import com.shelfj.reporting.domain.Domain.SalesSummary;
import com.shelfj.reporting.repo.ReportingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ReportingService {

  @Inject ReportingRepository repo;

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  public List<InventoryProjection> onHand(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.queryOnHand(tenantId, storeId, variantId);
  }

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  public record NettingResult(List<InventoryProjection> onHand, List<OpenSupplyLine> supplyLines) {}

  public NettingResult supplyDemandNetting(UUID tenantId, UUID storeId, UUID variantId) {
    return new NettingResult(
        repo.queryOnHand(tenantId, storeId, variantId),
        repo.querySupplyLines(tenantId, storeId, variantId));
  }

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  public List<MovementStat> movementStats(
      UUID tenantId, UUID storeId, UUID variantId, int bucketDays) {
    int days = (bucketDays < 1 || bucketDays > 365) ? 7 : bucketDays;
    return repo.queryMovementStats(tenantId, storeId, variantId, days);
  }

  // ── Projection update helpers (called by Kafka handlers) ─────────────────

  /**
   * Apply one signed stock delta (received/deducted/adjusted), deduped on eventId atomically with
   * the projection writes. Returns false if the event was already processed.
   */
  public boolean applyStockDeltaOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String eventType) {
    return repo.applyStockDeltaOnce(
        eventId, consumerName, tenantId, storeId, variantId, delta, eventType);
  }

  public void applyTransferShipped(
      UUID tenantId,
      UUID eventId,
      UUID fromStoreId,
      UUID toStoreId,
      List<UUID> variantIds,
      List<BigDecimal> qtys) {
    for (int i = 0; i < variantIds.size(); i++) {
      repo.insertSupplyLine(
          new com.shelfj.reporting.domain.Domain.OpenSupplyLine(
              UUID.randomUUID(),
              tenantId,
              fromStoreId,
              toStoreId,
              variantIds.get(i),
              qtys.get(i),
              eventId));
    }
  }

  public void applyTransferReceived(UUID eventId) {
    repo.deleteSupplyLinesByEvent(eventId);
  }

  // ── N4: Sales reporting ──────────────────────────────────────────────────

  /** Project a confirmed order into the sales facts (idempotent on the order PK). */
  public void recordSale(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal gross,
      String currency) {
    repo.recordSaleOnce(tenantId, orderId, storeId, channel, customerId, gross, currency);
  }

  /** Add a refund to a sale's projected total, deduped on the payment event's eventId. */
  public void applySalesRefund(
      UUID eventId, String consumer, UUID tenantId, UUID orderId, BigDecimal amount) {
    repo.applySalesRefundOnce(eventId, consumer, tenantId, orderId, amount);
  }

  public List<SalesSummary> salesSummary(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesSummary(tenantId, from, to, storeId, channel);
  }

  public List<SalesDayStat> salesByDay(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesByDay(tenantId, from, to, storeId, channel);
  }
}
