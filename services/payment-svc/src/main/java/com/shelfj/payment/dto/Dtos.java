package com.shelfj.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class Dtos {

  private Dtos() {}

  @Schema(
      name = "RecordTenderRequest",
      description =
          "Payment tender to capture for an order, either staff-recorded (POS) or online.")
  public record RecordTenderRequest(
      @Schema(description = "UUID of the order this tender is captured against.") @NotBlank
          String orderId,
      @Schema(description = "Amount tendered, in the order's currency.")
          @NotNull
          @DecimalMin("0.01")
          BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER, or STORE_CREDIT.")
          @NotBlank
          String method,
      String reference,
      @Schema(
              description =
                  "Client-supplied idempotency key; the Idempotency-Key header takes"
                      + " precedence when both are present.")
          String idempotencyKey,
      String notes,
      @Schema(description = "UUID of the store the tender is attributed to.") String storeId,
      // Required only for STORE_CREDIT tenders: the customer whose balance is redeemed, and the
      // currency of that balance (defaults to GBP).
      @Schema(
              description =
                  "Required for STORE_CREDIT tenders: the customer whose balance is" + " redeemed.")
          String customerId,
      @Schema(description = "ISO currency code of the store-credit balance; defaults to GBP.")
          String currency) {}

  @Schema(
      name = "RecordRefundRequest",
      description = "Refund against a previously captured tender.")
  public record RecordRefundRequest(
      @Schema(description = "UUID of the payment tender being refunded.") @NotBlank
          String paymentId,
      @Schema(description = "Amount to refund; capped at the tender's remaining captured total.")
          @NotNull
          @DecimalMin("0.01")
          BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, or VOUCHER.") @NotBlank
          String method,
      String reference,
      String idempotencyKey,
      @Schema(description = "Reason for the refund.") String reason) {}

  @Schema(name = "TenderResponse", description = "A captured (append-only) payment tender.")
  public record TenderResponse(
      UUID id,
      UUID orderId,
      @Schema(description = "Amount tendered, in the order's currency.") BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER, or STORE_CREDIT.")
          String method,
      String reference,
      @Schema(description = "Tender status, e.g. CAPTURED.") String status,
      String notes,
      Instant createdAt) {}

  @Schema(name = "RefundResponse", description = "A recorded (append-only) refund.")
  public record RefundResponse(
      UUID id,
      UUID orderId,
      @Schema(description = "UUID of the tender this refund is drawn against.") UUID paymentId,
      @Schema(description = "Amount refunded, in the order's currency.") BigDecimal amount,
      @Schema(description = "CASH, CARD, UPI, WALLET, GIFT_CARD, or VOUCHER.") String method,
      String reference,
      String reason,
      Instant createdAt) {}

  // ── Cash management ────────────────────────────────────────────────────────

  @Schema(name = "OpenTillRequest", description = "Open a till session with an opening cash float.")
  public record OpenTillRequest(
      @Schema(description = "UUID of the store the till session is opened at.") @NotBlank
          String storeId,
      @Schema(description = "Opening cash float amount.") @NotNull @PositiveOrZero
          BigDecimal floatAmount) {}

  @Schema(name = "TillSessionResponse", description = "A cashier till session.")
  public record TillSessionResponse(
      UUID id,
      UUID storeId,
      @Schema(description = "UUID of the user who opened the session.") UUID openedBy,
      BigDecimal floatAmount,
      @Schema(description = "OPEN or CLOSED.") String status,
      @Schema(description = "Physically counted cash at close time; null while open.")
          BigDecimal countedCash,
      @Schema(description = "countedCash minus expected cash; null while open.")
          BigDecimal overShort,
      Instant openedAt,
      @Schema(description = "Null while the session is still open.") Instant closedAt) {}

  @Schema(name = "RecordCashDropRequest", description = "Mid-shift safe drop from the till.")
  public record RecordCashDropRequest(
      @Schema(description = "Amount removed from the till.") @NotNull @DecimalMin("0.01")
          BigDecimal amount,
      String notes) {}

  @Schema(name = "CashDropResponse", description = "A recorded (append-only) cash drop.")
  public record CashDropResponse(
      UUID id, UUID tillSessionId, BigDecimal amount, Instant createdAt) {}

  @Schema(name = "CloseTillRequest", description = "Close a till session (Z-report).")
  public record CloseTillRequest(
      @Schema(description = "Physically counted cash in the till at close time.")
          @NotNull
          @PositiveOrZero
          BigDecimal countedCash) {}

  /** Breakdown of sales and refunds per tender method (used in X/Z reports). */
  @Schema(
      name = "TenderSummary",
      description = "Breakdown of sales and refunds for one tender method.")
  public record TenderSummary(BigDecimal sales, BigDecimal refunds, BigDecimal net) {}

  /** X-report (read-only snapshot) and Z-report (close) share this structure. */
  @Schema(
      name = "TillReportResponse",
      description =
          "Till totals report; shared shape for the read-only X-report and the closing Z-report.")
  public record TillReportResponse(
      UUID tillSessionId,
      UUID storeId,
      UUID openedBy,
      Instant openedAt,
      @Schema(description = "Null for an X-report (till still open).") Instant closedAt,
      BigDecimal floatAmount,
      @Schema(description = "Sales/refunds/net breakdown keyed by tender method.")
          Map<String, TenderSummary> tenderSummary,
      BigDecimal cashDropsTotal,
      @Schema(description = "Float plus cash sales minus cash refunds and cash drops.")
          BigDecimal expectedCashInTill,
      @Schema(description = "Physically counted cash; null for an X-report.")
          BigDecimal countedCash,
      @Schema(description = "countedCash minus expectedCashInTill; null for an X-report.")
          BigDecimal overShort,
      BigDecimal grossSales,
      BigDecimal totalRefunds,
      BigDecimal netSales) {}

  // ── Pay-in / Pay-out (petty cash) ─────────────────────────────────────────

  @Schema(name = "CashMovementRequest", description = "Petty cash pay-in or pay-out.")
  public record CashMovementRequest(
      @Schema(description = "UUID of the open till session this movement applies to.") @NotBlank
          String tillSessionId,
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "PAY_IN or PAY_OUT.") @NotBlank String direction,
      @Schema(description = "Amount moved.") @NotNull @DecimalMin("0.01") BigDecimal amount,
      @Schema(description = "Reason for the movement.") @NotBlank String reason,
      @Schema(description = "UUID of the user who authorised the movement, if applicable.")
          String authorisedBy) {}

  @Schema(name = "CashMovementResponse", description = "A recorded (append-only) cash movement.")
  public record CashMovementResponse(
      UUID id,
      UUID storeId,
      UUID tillSessionId,
      @Schema(description = "PAY_IN or PAY_OUT.") String direction,
      BigDecimal amount,
      String reason,
      Instant createdAt) {}

  // ── Daily Z-report ─────────────────────────────────────────────────────────

  @Schema(name = "GenerateZReportRequest", description = "Generate (or retrieve) a daily Z-report.")
  public record GenerateZReportRequest(
      @Schema(description = "UUID of the store.") @NotBlank String storeId,
      @Schema(description = "Business date the report covers, ISO-8601 yyyy-MM-dd.") @NotBlank
          String businessDate,
      @Schema(description = "Physically counted cash for the day.") @NotNull @PositiveOrZero
          BigDecimal countedCash,
      @Schema(description = "ISO currency code; defaults to GBP.") String currency) {}

  @Schema(
      name = "ZReportResponse",
      description = "Daily end-of-day settlement report (append-only).")
  public record ZReportResponse(
      UUID id,
      UUID storeId,
      LocalDate businessDate,
      BigDecimal totalSales,
      BigDecimal totalRefunds,
      BigDecimal totalDiscounts,
      BigDecimal totalTax,
      BigDecimal netSales,
      BigDecimal cashSales,
      BigDecimal cardSales,
      BigDecimal giftCardSales,
      @Schema(description = "Opening cash float for the till session(s) covered.")
          BigDecimal openingFloat,
      BigDecimal cashDrops,
      BigDecimal payIns,
      BigDecimal payOuts,
      @Schema(description = "Float plus cash sales minus cash refunds, drops, and pay-outs.")
          BigDecimal expectedCash,
      @Schema(description = "Physically counted cash for the day.") BigDecimal countedCash,
      @Schema(description = "countedCash minus expectedCash.") BigDecimal overShort,
      int transactionCount,
      @Schema(description = "ISO currency code.") String currency,
      Instant generatedAt) {}
}
