package com.shelfj.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Dtos {

  private Dtos() {}

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  public record OnHandRow(String storeId, String variantId, BigDecimal onHand, Instant updatedAt) {}

  public record OnHandReport(List<OnHandRow> rows, BigDecimal grandTotal) {}

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  public record NettingRow(
      String storeId,
      String variantId,
      BigDecimal onHand,
      BigDecimal supplyInTransit,
      BigDecimal netAvailable) {}

  public record NettingReport(List<NettingRow> rows) {}

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  public record MovementStatRow(
      String storeId,
      String variantId,
      String bucket,
      BigDecimal totalIn,
      BigDecimal totalOut,
      BigDecimal net) {}

  public record MovementStatsReport(List<MovementStatRow> rows) {}

  // ── N4: Sales reporting ──────────────────────────────────────────────────

  public record SalesSummaryRow(
      String currency, long orders, BigDecimal gross, BigDecimal refunded, BigDecimal net) {}

  public record SalesSummaryReport(List<SalesSummaryRow> rows) {}

  public record SalesDayRow(
      String day,
      String currency,
      long orders,
      BigDecimal gross,
      BigDecimal refunded,
      BigDecimal net) {}

  public record SalesByDayReport(List<SalesDayRow> rows) {}
}
