package com.storeql.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** EMV terminals and the card payments taken on them (07.16). */
public final class TerminalDtos {

  private TerminalDtos() {}

  @Schema(name = "CardTerminal")
  public record TerminalResponse(
      String id,
      String storeId,
      @Schema(description = "What the cashier sees, e.g. \"Till 2\".") String label,
      @Schema(description = "SIMULATED, STRIPE_TERMINAL, ADYEN or VERIFONE.") String vendor,
      @Schema(description = "The vendor's identifier for the device, as printed on it.")
          String serial,
      @Schema(description = "ACTIVE or RETIRED. A retired terminal is kept: payments point at it.")
          String status,
      String retiredReason,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "RegisterTerminalRequest")
  public record RegisterRequest(
      @NotBlank String storeId,
      @Schema(description = "What the cashier sees. Unique among a store's active terminals.")
          @NotBlank
          @Size(max = 60)
          String label,
      @Schema(description = "SIMULATED, STRIPE_TERMINAL, ADYEN or VERIFONE.")
          @NotBlank
          @Size(max = 30)
          String vendor,
      @Schema(description = "The device's serial, as printed on it.") @Size(max = 80)
          String serial) {}

  @Schema(name = "RetireTerminalRequest")
  public record RetireRequest(
      @Schema(
              description =
                  "Why, so a device withdrawn after a fault is distinguishable from one replaced on"
                      + " an upgrade.")
          @Size(max = 300)
          String reason) {}

  /**
   * A card payment on a terminal, and what the terminal said.
   *
   * @param state REQUESTED, APPROVED, DECLINED, CANCELLED, FAILED or TIMED_OUT. A TIMED_OUT attempt
   *     is the one to act on: the card may have been charged, so no tender is recorded and it is
   *     reconciled against the acquirer's settlement file
   * @param panLast4 the four digits a receipt may print. No other part of the number exists
   *     anywhere in this platform
   * @param receiptLine what a receipt prints for the card, already assembled
   */
  @Schema(name = "TerminalPayment")
  public record AttemptResponse(
      String id,
      String terminalId,
      String orderId,
      String amount,
      String currency,
      @Schema(description = "SALE or REFUND.") String kind,
      String refundOf,
      String state,
      @Schema(description = "The terminal's own words when it declined or failed.")
          String outcomeDetail,
      String scheme,
      String panLast4,
      String authCode,
      @Schema(description = "The EMV application the card ran, e.g. A0000000031010.") String aid,
      @Schema(description = "What the receipt prints for it, e.g. VISA DEBIT.")
          String applicationLabel,
      @Schema(description = "CHIP, CONTACTLESS, SWIPE or MANUAL.") String entryMode,
      @Schema(description = "PIN, SIGNATURE, NONE or DEVICE.") String verification,
      String providerRef,
      @Schema(description = "The tender this became, once approved.") String paymentId,
      String receiptLine,
      String requestedAt,
      String settledAt) {}

  /**
   * Takes a card for an order.
   *
   * <p>There is no field for a card number, and there never will be: the terminal reads the card.
   */
  @Schema(name = "TerminalSaleRequest")
  public record SaleRequest(
      @NotBlank String terminalId,
      @NotBlank String orderId,
      @NotNull @Positive BigDecimal amount,
      @NotBlank @Size(min = 3, max = 3) String currency) {}

  /**
   * Puts money back on the card that paid. Linked to the attempt, never to a card presented again.
   */
  @Schema(name = "TerminalRefundRequest")
  public record RefundRequest(@NotNull @Positive BigDecimal amount) {}
}
