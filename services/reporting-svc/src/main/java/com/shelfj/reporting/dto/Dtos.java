package com.shelfj.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for reporting-svc — the wire contract for the report endpoints.
 *
 * <p>Ids are carried as {@code String} rather than {@code UUID} so the JSON contract stays stable,
 * and money is {@code BigDecimal} throughout.
 */
public final class Dtos {

  private Dtos() {}

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  @Schema(name = "OnHandRow", description = "On-hand quantity for one store/variant pair.")
  public record OnHandRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal onHand,
      @Schema(description = "Timestamp of the last projected stock event for this row.")
          Instant updatedAt) {}

  @Schema(name = "OnHandReport", description = "Cross-store on-hand snapshot.")
  public record OnHandReport(
      List<OnHandRow> rows,
      @Schema(description = "Sum of onHand across all returned rows.") BigDecimal grandTotal) {}

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  @Schema(
      name = "NettingRow",
      description = "On-hand netted against open in-transit supply for one store/variant pair.")
  public record NettingRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      BigDecimal onHand,
      @Schema(description = "Quantity on open in-transit supply lines (e.g. transfers/POs).")
          BigDecimal supplyInTransit,
      @Schema(description = "onHand + supplyInTransit.") BigDecimal netAvailable) {}

  @Schema(name = "NettingReport", description = "Supply/demand netting report.")
  public record NettingReport(List<NettingRow> rows) {}

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  @Schema(
      name = "MovementStatRow",
      description = "Bucketed stock movement totals for one store/variant pair.")
  public record MovementStatRow(
      @Schema(description = "UUID of the store.") String storeId,
      @Schema(description = "UUID of the product variant.") String variantId,
      @Schema(description = "Bucket label/start date, sized by the requested bucketDays.")
          String bucket,
      @Schema(description = "Total quantity received/adjusted in during this bucket.")
          BigDecimal totalIn,
      @Schema(description = "Total quantity deducted/adjusted out during this bucket.")
          BigDecimal totalOut,
      @Schema(description = "totalIn - totalOut.") BigDecimal net) {}

  @Schema(name = "MovementStatsReport", description = "Movement-statistics report.")
  public record MovementStatsReport(List<MovementStatRow> rows) {}

  // ── N4: Sales reporting ──────────────────────────────────────────────────

  @Schema(name = "SalesSummaryRow", description = "Revenue summary for one currency.")
  public record SalesSummaryRow(
      String currency,
      @Schema(description = "Number of confirmed orders in the range.") long orders,
      @Schema(description = "Total order revenue before refunds.") BigDecimal gross,
      @Schema(description = "Total refunded amount in the range.") BigDecimal refunded,
      @Schema(description = "gross - refunded.") BigDecimal net) {}

  @Schema(name = "SalesSummaryReport", description = "Sales revenue summary, one row per currency.")
  public record SalesSummaryReport(List<SalesSummaryRow> rows) {}

  @Schema(name = "SalesDayRow", description = "Revenue for a single calendar day and currency.")
  public record SalesDayRow(
      @Schema(description = "Calendar day, ISO yyyy-MM-dd.") String day,
      String currency,
      @Schema(description = "Number of confirmed orders on this day.") long orders,
      @Schema(description = "Total order revenue before refunds.") BigDecimal gross,
      @Schema(description = "Total refunded amount on this day.") BigDecimal refunded,
      @Schema(description = "gross - refunded.") BigDecimal net) {}

  @Schema(name = "SalesByDayReport", description = "Daily sales revenue buckets, newest day first.")
  public record SalesByDayReport(List<SalesDayRow> rows) {}
}
