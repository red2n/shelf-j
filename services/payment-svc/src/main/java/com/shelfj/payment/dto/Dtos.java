package com.shelfj.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class Dtos {

  private Dtos() {}

  public record RecordTenderRequest(
      @NotBlank String orderId,
      @NotNull @DecimalMin("0.01") BigDecimal amount,
      @NotBlank String method,
      String reference,
      String idempotencyKey,
      String notes) {}

  public record RecordRefundRequest(
      @NotBlank String paymentId,
      @NotNull @DecimalMin("0.01") BigDecimal amount,
      @NotBlank String method,
      String reference,
      String idempotencyKey,
      String reason) {}

  public record TenderResponse(
      UUID id,
      UUID orderId,
      BigDecimal amount,
      String method,
      String reference,
      String status,
      String notes,
      Instant createdAt) {}

  public record RefundResponse(
      UUID id,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      String reference,
      String reason,
      Instant createdAt) {}

  // ── Cash management ────────────────────────────────────────────────────────

  public record OpenTillRequest(
      @NotBlank String storeId, @NotNull @PositiveOrZero BigDecimal floatAmount) {}

  public record TillSessionResponse(
      UUID id,
      UUID storeId,
      UUID openedBy,
      BigDecimal floatAmount,
      String status,
      BigDecimal countedCash,
      BigDecimal overShort,
      Instant openedAt,
      Instant closedAt) {}

  public record RecordCashDropRequest(
      @NotNull @DecimalMin("0.01") BigDecimal amount, String notes) {}

  public record CashDropResponse(
      UUID id, UUID tillSessionId, BigDecimal amount, Instant createdAt) {}

  public record CloseTillRequest(@NotNull @PositiveOrZero BigDecimal countedCash) {}

  /** Breakdown of sales and refunds per tender method (used in X/Z reports). */
  public record TenderSummary(BigDecimal sales, BigDecimal refunds, BigDecimal net) {}

  /** X-report (read-only snapshot) and Z-report (close) share this structure. */
  public record TillReportResponse(
      UUID tillSessionId,
      UUID storeId,
      UUID openedBy,
      Instant openedAt,
      Instant closedAt,
      BigDecimal floatAmount,
      Map<String, TenderSummary> tenderSummary,
      BigDecimal cashDropsTotal,
      BigDecimal expectedCashInTill,
      BigDecimal countedCash,
      BigDecimal overShort,
      BigDecimal grossSales,
      BigDecimal totalRefunds,
      BigDecimal netSales) {}
}
