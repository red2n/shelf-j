package com.shelfj.reporting.service;

import com.shelfj.reporting.domain.Domain.InventoryProjection;
import com.shelfj.reporting.domain.Domain.MovementStat;
import com.shelfj.reporting.domain.Domain.OpenSupplyLine;
import com.shelfj.reporting.repo.ReportingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
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

  public void applyStockReceived(UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty) {
    repo.upsertProjection(tenantId, storeId, variantId, qty);
    repo.insertMovementEvent(tenantId, storeId, variantId, "StockReceived", qty);
  }

  public void applyStockDeducted(UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty) {
    repo.upsertProjection(tenantId, storeId, variantId, qty.negate());
    repo.insertMovementEvent(tenantId, storeId, variantId, "StockDeducted", qty.negate());
  }

  public void applyStockAdjusted(UUID tenantId, UUID storeId, UUID variantId, BigDecimal delta) {
    repo.upsertProjection(tenantId, storeId, variantId, delta);
    repo.insertMovementEvent(tenantId, storeId, variantId, "StockAdjusted", delta);
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
}
