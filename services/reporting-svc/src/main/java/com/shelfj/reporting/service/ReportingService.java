package com.shelfj.reporting.service;

import com.shelfj.ids.Ids;
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

/**
 * Business logic for reporting-svc. Thin resource → this service → repository.
 *
 * <p>Two distinct roles: the {@code apply*}/{@code record*} methods are the write side, called by
 * the Kafka handlers to fold domain events into local projections; the query methods are the read
 * side behind the report endpoints. reporting-svc owns no source-of-truth data — every projection
 * is derived from events published by inventory-svc, order-svc and payment-svc, so reports are
 * eventually consistent with those services.
 */
@ApplicationScoped
public class ReportingService {

  @Inject ReportingRepository repo;

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  /**
   * Cross-store on-hand quantities, projected from consumed stock-movement events.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @return one row per store/variant pair with stock on hand
   */
  public List<InventoryProjection> onHand(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.queryOnHand(tenantId, storeId, variantId);
  }

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  /**
   * On-hand stock paired with the in-transit lines that will land against it.
   *
   * @param onHand current on-hand rows per store/variant
   * @param supplyLines open transfer lines shipped but not yet received
   */
  public record NettingResult(List<InventoryProjection> onHand, List<OpenSupplyLine> supplyLines) {}

  /**
   * Nets on-hand stock against open in-transit supply to show net available.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @return the on-hand rows and the open supply lines to net them against
   */
  public NettingResult supplyDemandNetting(UUID tenantId, UUID storeId, UUID variantId) {
    return new NettingResult(
        repo.queryOnHand(tenantId, storeId, variantId),
        repo.querySupplyLines(tenantId, storeId, variantId));
  }

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  /**
   * Stock in/out/net totals bucketed over a fixed window.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @param bucketDays days per bucket (1 daily, 7 weekly, 30 monthly-ish); values outside 1..365
   *     are silently coerced to 7 rather than rejected, so a nonsense query still returns a report
   * @return one row per bucket per store/variant
   */
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

  /**
   * Opens an in-transit supply line per variant when a stock transfer ships.
   *
   * <p>The lines are keyed by {@code eventId} so {@link #applyTransferReceived} can retire exactly
   * this shipment's lines when the goods land.
   *
   * @param tenantId owning tenant
   * @param eventId the {@code TransferShipped} event id, retained as the retirement key
   * @param fromStoreId store the stock left
   * @param toStoreId store the stock is bound for
   * @param variantIds variants shipped, positionally paired with {@code qtys}
   * @param qtys quantities shipped, positionally paired with {@code variantIds}
   */
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
              Ids.newId(),
              tenantId,
              fromStoreId,
              toStoreId,
              variantIds.get(i),
              qtys.get(i),
              eventId));
    }
  }

  /**
   * Closes the in-transit supply lines opened by the matching shipment.
   *
   * <p>Idempotent by construction: a redelivered event deletes rows that are already gone.
   *
   * @param eventId the {@code TransferShipped} event id the lines were opened under
   */
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

  /**
   * Aggregate sales totals over a period.
   *
   * @param tenantId owning tenant
   * @param from inclusive start of the period, UTC
   * @param to exclusive end of the period, UTC
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null} for both
   * @return the summary rows, net of any refunds already projected
   */
  public List<SalesSummary> salesSummary(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesSummary(tenantId, from, to, storeId, channel);
  }

  /**
   * Sales totals broken down by calendar day.
   *
   * @param tenantId owning tenant
   * @param from inclusive start of the period, UTC
   * @param to exclusive end of the period, UTC
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null} for both
   * @return one row per day in the period that saw a sale
   */
  public List<SalesDayStat> salesByDay(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesByDay(tenantId, from, to, storeId, channel);
  }
}
