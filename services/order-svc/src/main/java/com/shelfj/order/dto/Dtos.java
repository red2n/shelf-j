package com.shelfj.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for order-svc. tenant_id never in request — comes from JWT context. */
public final class Dtos {

  private Dtos() {}

  // ── Order ─────────────────────────────────────────────────────────────────

  @Schema(name = "OrderItemRequest")
  public record OrderItemRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @Schema(
              description =
                  "Optional when server-side pricing is enforced (ignored there); required and"
                      + " trusted only in legacy mode. Zero is allowed (e.g. a price-hidden"
                      + " storefront that never resolves a real price client-side, or a genuine"
                      + " free/comped item) — server-side pricing enforcement re-resolves the"
                      + " real price regardless.")
          @PositiveOrZero
          BigDecimal unitPrice,
      String notes,
      @Schema(
              description =
                  "For a line sold by weight: the weighing instrument the reading came from, from"
                      + " tenant-svc's register (Weights and Measures Act 1985 s.11). The till"
                      + " refuses to sell by weight from an instrument that is not certified, and"
                      + " the line records which one it was.")
          String weighingInstrumentId) {}

  @Schema(
      name = "PlaceOrderRequest",
      description = "Places an order. tenantId comes from the caller's JWT, never from this body.")
  public record PlaceOrderRequest(
      @Schema(description = "UUID of the store this order is placed at.") @NotBlank String storeId,
      @Schema(description = "UUID of the customer; ignored for a signed-in customer caller.")
          String customerId,
      @Schema(description = "ONLINE or POS.") @NotBlank String channel,
      @Schema(description = "INSTORE, DELIVERY, etc. Defaults to in-store.") String fulfilmentType,
      @NotNull @Valid List<OrderItemRequest> items,
      @Schema(description = "Ignored when server-side pricing enforcement is on.") @PositiveOrZero
          BigDecimal taxAmount,
      @Schema(
              description =
                  "Manual staff discount off the subtotal. Honoured whether or not pricing"
                      + " enforcement is on: staff only, never above the subtotal, and never above"
                      + " the caller role's configured percentage ceiling. Requires"
                      + " discountReason.")
          @PositiveOrZero
          BigDecimal discountAmount,
      @Schema(description = "Why the discount was given. Required whenever discountAmount is set.")
          String discountReason,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String notes,
      @Schema(
              description =
                  "Coupon codes the customer presented, matched case-insensitively. Codes that do"
                      + " not apply are reported by pricing-svc and do not fail the order.")
          List<String> couponCodes,
      @Schema(description = "Legacy fallback for the Idempotency-Key header.")
          String idempotencyKey,
      Boolean taxExempt,
      String exemptReason,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryLine1,
      String deliveryLine2,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryCity,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryPostalCode,
      @Schema(description = "Required when fulfilmentType is DELIVERY.")
          String deliveryRecipientName,
      @Schema(description = "Required when fulfilmentType is DELIVERY.")
          String deliveryRecipientPhone,
      String contactPhone,
      @Schema(
              description =
                  "How the customer intends to pay: CASH, CARD, UPI, or WALLET. CASH + DELIVERY is"
                      + " cash-on-delivery; settlement itself is recorded by payment-svc.")
          String paymentMethod) {}

  @Schema(name = "OrderItemResponse")
  public record OrderItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes,
      @Schema(description = "The instrument a sold-by-weight line was weighed on; null otherwise.")
          String weighingInstrumentId,
      @Schema(description = "How much of qty has been handed over so far (SJ-D35).")
          BigDecimal fulfilledQty) {}

  @Schema(
      name = "FulfilRequest",
      description =
          "Which lines, and how much of each, are being handed over now. Omit the body, or the"
              + " lines, to hand over everything still outstanding.")
  public record FulfilRequest(List<FulfilLine> lines) {}

  @Schema(name = "FulfilLine")
  public record FulfilLine(
      @NotNull String variantId,
      @Schema(description = "Units handed over now; at most what is still outstanding.")
          @NotNull
          @jakarta.validation.constraints.Positive
          BigDecimal qty) {}

  @Schema(name = "OrderResponse")
  public record OrderResponse(
      String id,
      String storeId,
      String customerId,
      @Schema(
              description =
                  "The login that placed the order, for an online sale by a signed-in shopper."
                      + " Distinct from customerId, which is the shop's own record of that person:"
                      + " a login is global and a customer record is per-tenant (SJ-D44). Null for"
                      + " a guest checkout and for a till sale.")
          String loginId,
      @Schema(description = "ONLINE or POS.") String channel,
      String fulfilmentType,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, CANCELLED, or VOIDED.") String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      @Schema(
              description =
                  "The staff discount: a deliberate act by a named person, with a reason and a"
                      + " role ceiling. Automatic promotional money is promotionDiscount, kept"
                      + " apart so an offer cannot spend a cashier's authority.")
          BigDecimal discountAmount,
      @Schema(
              description =
                  "What the promotion engine took off the basket as a whole. Line-level"
                      + " promotions are already inside the line prices and therefore inside"
                      + " subtotal.")
          BigDecimal promotionDiscount,
      BigDecimal total,
      String currency,
      String notes,
      String createdAt,
      String updatedAt,
      List<OrderItemResponse> items,
      boolean taxExempt,
      String exemptReason,
      String deliveryLine1,
      String deliveryLine2,
      String deliveryCity,
      String deliveryPostalCode,
      String deliveryRecipientName,
      String deliveryRecipientPhone,
      String contactPhone,
      @Schema(description = "CASH, CARD, UPI, or WALLET.") String paymentMethod) {}

  @Schema(name = "OrderStatusHistoryResponse", description = "Append-only order status transition.")
  public record OrderStatusHistoryResponse(
      String id,
      String orderId,
      String fromStatus,
      String toStatus,
      String reason,
      String changedBy,
      String changedAt) {}

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  @Schema(name = "ReturnItemRequest")
  public record ReturnItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @Schema(description = "Condition of the returned item, e.g. NEW, DAMAGED.")
          String condition) {}

  @Schema(name = "CreateReturnRequest")
  public record CreateReturnRequest(
      @NotBlank String reason,
      @Schema(description = "Defaults to refunding via the order's original payment method.")
          String refundMethod,
      @NotNull @Valid List<ReturnItemRequest> items) {}

  @Schema(name = "ReturnItemResponse")
  public record ReturnItemResponse(
      String id, String variantId, BigDecimal qty, BigDecimal refundAmount, String condition) {}

  @Schema(name = "ReturnResponse")
  public record ReturnResponse(
      String id,
      String orderId,
      String storeId,
      String reason,
      BigDecimal refundAmount,
      String refundMethod,
      String status,
      String createdAt,
      String completedAt,
      List<ReturnItemResponse> items) {}

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  // ── Order list (header-only, no items embedded) ───────────────────────────

  @Schema(name = "OrderSummaryResponse", description = "Order header only, without line items.")
  public record OrderSummaryResponse(
      String id,
      String storeId,
      String customerId,
      @Schema(description = "ONLINE or POS.") String channel,
      String fulfilmentType,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, CANCELLED, or VOIDED.") String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      String createdAt,
      String updatedAt,
      String paymentMethod) {}

  @Schema(name = "VoidRequest")
  public record VoidRequest(@NotBlank String reason) {}

  @Schema(name = "VoidResponse")
  public record VoidResponse(String orderId, String reason, String voidedAt) {}

  // ── Layaway (Gap #14) ─────────────────────────────────────────────────────

  @Schema(name = "LayawayItemRequest")
  public record LayawayItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice) {}

  @Schema(name = "CreateLayawayRequest")
  public record CreateLayawayRequest(
      @NotBlank String storeId,
      String customerId,
      @NotNull @Valid List<LayawayItemRequest> items,
      @Schema(description = "Initial deposit; cannot exceed the layaway's total amount.")
          @NotNull
          @Positive
          BigDecimal initialDeposit,
      String paymentMethod,
      String dueDate,
      String notes) {}

  @Schema(name = "AddDepositRequest")
  public record AddDepositRequest(
      @NotNull @Positive BigDecimal amount, @NotBlank String paymentMethod, String reference) {}

  @Schema(name = "LayawayItemResponse")
  public record LayawayItemResponse(
      String id, String variantId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

  @Schema(name = "LayawayDepositResponse")
  public record LayawayDepositResponse(
      String id, BigDecimal amount, String paymentMethod, String reference, String paidAt) {}

  @Schema(name = "LayawayResponse")
  public record LayawayResponse(
      String id,
      String storeId,
      String customerId,
      BigDecimal totalAmount,
      BigDecimal depositPaid,
      @Schema(description = "Total amount minus deposits paid so far.") BigDecimal balance,
      @Schema(description = "ACTIVE, COMPLETED, or CANCELLED.") String status,
      String notes,
      String createdAt,
      String dueDate,
      String completedAt,
      String cancelledAt,
      List<LayawayItemResponse> items,
      List<LayawayDepositResponse> deposits) {}

  // ── Gift cards (Gap #14) ──────────────────────────────────────────────────

  @Schema(name = "IssueGiftCardRequest")
  public record IssueGiftCardRequest(
      @NotBlank String storeId,
      @Schema(description = "Initial stored-value amount.") @NotNull @Positive BigDecimal amount,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String expiresAt) {}

  @Schema(name = "ReloadGiftCardRequest")
  public record ReloadGiftCardRequest(@NotNull @Positive BigDecimal amount, String reference) {}

  @Schema(name = "RedeemGiftCardRequest")
  public record RedeemGiftCardRequest(
      @NotNull @Positive BigDecimal amount,
      @Schema(description = "UUID of the order this redemption pays for, if any.") String orderId,
      String reference) {}

  @Schema(name = "GiftCardResponse")
  public record GiftCardResponse(
      String id,
      String storeId,
      String code,
      BigDecimal initialBalance,
      BigDecimal currentBalance,
      @Schema(description = "ACTIVE, DEPLETED, or EXPIRED.") String status,
      String currency,
      String issuedAt,
      String expiresAt) {}

  @Schema(name = "GiftCardTransactionResponse")
  public record GiftCardTransactionResponse(
      String id,
      @Schema(description = "ISSUE, RELOAD, or REDEEM.") String txType,
      BigDecimal amount,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      String orderId,
      String reference,
      String createdAt) {}

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  @Schema(name = "SpecialOrderItemRequest")
  public record SpecialOrderItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice,
      String notes) {}

  @Schema(
      name = "CreateSpecialOrderRequest",
      description =
          "Places a customer order for future delivery at a store, without immediate inventory"
              + " deduction.")
  public record CreateSpecialOrderRequest(
      @NotBlank String storeId,
      String customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      @Schema(description = "ISO-8601 date the customer has requested delivery by.")
          String requestedDeliveryDate,
      String notes,
      @NotNull @Valid List<SpecialOrderItemRequest> items,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String idempotencyKey) {}

  @Schema(name = "SpecialOrderItemResponse")
  public record SpecialOrderItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  @Schema(name = "SpecialOrderResponse")
  public record SpecialOrderResponse(
      String id,
      String storeId,
      String customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      String requestedDeliveryDate,
      String notes,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, or CANCELLED.") String status,
      BigDecimal subtotal,
      BigDecimal total,
      String currency,
      String createdAt,
      String updatedAt,
      List<SpecialOrderItemResponse> items) {}

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  @Schema(
      name = "PosLogEntryResponse",
      description = "Append-only POS transaction journal entry for a fulfilled order.")
  public record PosLogEntryResponse(
      String id,
      String orderId,
      String storeId,
      String cashierId,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      boolean taxExempt,
      String exemptReason,
      String transactionTs,
      String createdAt) {}

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  @Schema(name = "GenerateReceiptRequest")
  public record GenerateReceiptRequest(
      @Schema(description = "PRINT or EMAIL.") @NotBlank String receiptType,
      @Schema(description = "Required when receiptType is EMAIL.") String emailedTo,
      Integer printCount) {}

  @Schema(name = "OrderReceiptResponse")
  public record OrderReceiptResponse(
      String id,
      String orderId,
      String receiptType,
      String emailedTo,
      int printCount,
      String generatedAt) {}

  // ── Gap #50: SIM ↔ POS sync ───────────────────────────────────────────────

  @Schema(
      name = "PosStockPositionResponse",
      description =
          "Eventually-consistent local projection of on-hand stock for a store/variant, updated"
              + " asynchronously from inventory-svc events.")
  public record PosStockPositionResponse(
      String storeId, String variantId, String onHandQty, String updatedAt) {}

  // ── Parked (suspended) sales ──────────────────────────────────────────────

  @Schema(name = "ParkedSaleItemRequest")
  public record ParkedSaleItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @NotNull @PositiveOrZero BigDecimal unitPrice,
      @PositiveOrZero BigDecimal discountAmount,
      String notes) {}

  @Schema(name = "ParkSaleRequest")
  public record ParkSaleRequest(
      @NotBlank String storeId,
      String customerId,
      String customerName,
      List<@NotNull ParkedSaleItemRequest> items,
      String notes) {}

  @Schema(name = "ResumeParkedSaleRequest")
  public record ResumeParkedSaleRequest(
      @Schema(description = "UUID of the parked sale to resume.") @NotBlank String parkedSaleId) {}

  @Schema(name = "ParkedSaleItemResponse")
  public record ParkedSaleItemResponse(
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal discountAmount,
      BigDecimal lineTotal,
      String notes) {}

  @Schema(name = "ParkedSaleResponse")
  public record ParkedSaleResponse(
      String id,
      String storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      List<ParkedSaleItemResponse> items,
      String notes,
      String parkedAt,
      @Schema(description = "When this parked sale is auto-discarded if not resumed.")
          String expiresAt) {}

  // ── No-sale / open-drawer log ─────────────────────────────────────────────

  @Schema(
      name = "NoSaleRequest",
      description = "Logs a cash-drawer open with no accompanying sale.")
  public record NoSaleRequest(String storeId, String tillSessionId, String reason) {}

  @Schema(name = "NoSaleResponse")
  public record NoSaleResponse(String id, String storeId, String reason, String loggedAt) {}

  // ── Staff exception report ────────────────────────────────────────────────

  @Schema(
      name = "ExceptionRowResponse",
      description =
          "One cashier's, or one store's, staff-initiated exceptions over the period, with the"
              + " journalled sales that make them a rate rather than a ranking of who worked most.")
  public record ExceptionRowResponse(
      @Schema(
              description =
                  "The actor id or store id this line covers. UNATTRIBUTED covers exceptions"
                      + " recorded with no actor — bucketed rather than dropped, because an"
                      + " exception nobody is accountable for is the last one to hide.")
          String groupKey,
      @Schema(description = "How many discounts this group granted.") long discounts,
      @Schema(description = "Total value discounted.") BigDecimal discountAmount,
      @Schema(description = "How many sales this group voided.") long voids,
      @Schema(description = "How many times the drawer was opened with no sale.") long noSales,
      @Schema(
              description =
                  "Journalled POS sales for this group. Zero means nothing journalled the sale,"
                      + " which is NOT the same as no sales having happened — see"
                      + " ExceptionReportResponse.journalCoverage before reading any rate.")
          long sales,
      @Schema(description = "Value of those journalled sales.") BigDecimal salesValue) {}

  @Schema(
      name = "ExceptionReportResponse",
      description = "Staff exception report: discounts, voids and no-sales over a period.")
  public record ExceptionReportResponse(
      List<ExceptionRowResponse> rows,
      @Schema(
              description =
                  "True when the POS transaction journal has entries for this period. When false"
                      + " every sales figure above is zero because nothing journalled anything,"
                      + " and the exception counts must be read as raw counts with no denominator"
                      + " — a cashier who took a thousand sales and one who took three are not"
                      + " distinguishable.")
          boolean journalCoverage) {}

  @Schema(
      name = "SalesByHourRowResponse",
      description = "One hour of the trading day, on the clock of the requested timezone.")
  public record SalesByHourRowResponse(
      @Schema(
              description =
                  "Hour 0-23 in the timezone the report was asked for, not UTC. Hours with no"
                      + " trade are absent rather than zero: a row of zeroes would assert the shop"
                      + " was open and empty.")
          int hourOfDay,
      @Schema(description = "How many revenue orders fell in this hour.") long orders,
      @Schema(description = "Their total, after discount.") BigDecimal grossAmount,
      @Schema(description = "How much was discounted away inside this hour.")
          BigDecimal discountAmount,
      @Schema(description = "grossAmount divided by orders.") BigDecimal averageBasket) {}

  @Schema(
      name = "SalesByStaffRowResponse",
      description =
          "One member of staff's takings, from the POS transaction journal. In-store only — an"
              + " online order has no cashier.")
  public record SalesByStaffRowResponse(
      @Schema(
              description =
                  "The cashier's user id. UNATTRIBUTED covers journal entries naming nobody —"
                      + " bucketed rather than dropped, because a sale with no cashier is a gap in"
                      + " the audit trail.")
          String groupKey,
      @Schema(description = "How many sales they journalled.") long sales,
      @Schema(description = "What those sales came to, after discount.") BigDecimal grossAmount,
      @Schema(description = "How much they discounted away.") BigDecimal discountAmount,
      @Schema(description = "grossAmount divided by sales.") BigDecimal averageBasket,
      @Schema(
              description =
                  "Discount as a percentage of what the sales would have fetched undiscounted."
                      + " Null when there is nothing to take a percentage of.")
          BigDecimal discountRate) {}

  // ── Age verification: the due-diligence record ──────────────────────────────

  @Schema(
      name = "RecordAgeCheckRequest",
      description =
          "One age check as the till made it. The rule fields are copied from product-svc's answer"
              + " at that moment, so the record says what the rule was rather than what it later"
              + " became.")
  public record RecordAgeCheckRequest(
      @jakarta.validation.constraints.NotBlank String storeId,
      @jakarta.validation.constraints.NotBlank String variantId,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "ALCOHOL, TOBACCO, KNIVES, … — product-svc's category.")
          String category,
      @jakarta.validation.constraints.NotNull
          @jakarta.validation.constraints.Min(1)
          @jakarta.validation.constraints.Max(99)
          Integer minimumAge,
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z]{2}$")
          @Schema(description = "ISO 3166-1 alpha-2 country whose rule applied.")
          String country,
      @Schema(description = "True when the age came from the shop's own stricter policy.")
          Boolean storePolicy,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "PASSED — the sale went ahead; REFUSED — it did not.")
          String outcome,
      @Schema(
              description =
                  "Required on a refusal, absent on a pass: UNDER_AGE, NO_ID, ID_REJECTED,"
                      + " PROXY_SALE (buying for someone under age) or OTHER.")
          String reason,
      @Schema(
              description =
                  "What was shown when the sale went ahead, if the shop records it: PASSPORT,"
                      + " DRIVING_LICENCE, PASS_CARD, MILITARY_ID, NATIONAL_ID or OTHER.")
          String idType,
      @Schema(description = "The POS session the check was made in, when the till has one.")
          String posSessionId,
      @Schema(description = "The sale the check belonged to, once there is one.") String orderId) {}

  @Schema(name = "AgeVerificationResponse", description = "One recorded age check.")
  public record AgeVerificationResponse(
      String id,
      String storeId,
      String cashierId,
      String posSessionId,
      String variantId,
      String category,
      int minimumAge,
      String country,
      boolean storePolicy,
      String outcome,
      String reason,
      String idType,
      String orderId,
      String checkedAt) {}

  @Schema(
      name = "AgeVerificationSummaryResponse",
      description = "Counts for one store and period, and the refusals broken down by reason.")
  public record AgeVerificationSummaryResponse(
      long total,
      long passed,
      long refused,
      java.util.Map<String, Long> refusedByReason,
      java.util.Map<String, Long> byCategory) {}

  @Schema(
      name = "SetReceiptSeriesRequest",
      description = "Open a receipt series, or set what it prints in front of the number.")
  public record SetReceiptSeriesRequest(
      @jakarta.validation.constraints.NotBlank String storeId,
      @Schema(description = "MAIN when omitted.") String seriesCode,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "The fiscal period, e.g. 2026.")
          String period,
      @Schema(description = "Letters, digits and hyphens, at most 16; blank for none.")
          String prefix) {}
}
