package com.shelfj.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain records for order-svc. Money is BigDecimal; times are UTC Instant. */
public final class Domain {

  private Domain() {}

  // ── Core order ────────────────────────────────────────────────────────────

  /**
   * A numbered legal receipt.
   *
   * <p>Distinct from {@code order_receipts}, which logs how many times a document was printed or
   * emailed. This is the document — one per sale, numbered consecutively, never renumbered and
   * never deleted. A voided sale keeps its number, because suppressing it is the fraud the
   * numbering exists to expose.
   */
  public record FiscalReceipt(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String seriesCode,
      String period,
      long number,
      String fullNumber,
      UUID orderId,
      Instant issuedAt,
      UUID issuedBy,
      String currency,
      BigDecimal grossTotal,
      BigDecimal taxTotal,
      Instant voidedAt,
      String voidReason) {
    /** The series a store uses when the jurisdiction does not require one per till. */
    public static final String DEFAULT_SERIES = "MAIN";
  }

  /** A hole in a receipt series, inclusive at both ends. */
  public record SequenceGap(long from, long to) {}

  public record Order(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      String channel,
      String fulfilmentType,
      String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      String notes,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt,
      boolean taxExempt,
      String exemptReason,
      String deliveryLine1,
      String deliveryLine2,
      String deliveryCity,
      String deliveryPostalCode,
      String deliveryRecipientName,
      String deliveryRecipientPhone,
      String contactPhone,
      String paymentMethod,
      /**
       * What the promotion engine took off this order, kept apart from {@code discountAmount}. That
       * one is the staff discount — a named person, a required reason, a role ceiling and an audit
       * row (SJ-D6). This one is automatic and answers to a rule. Summing them would put
       * promotional money inside the role-ceiling check.
       */
      BigDecimal promotionDiscount) {
    public static final String CHANNEL_ONLINE = "ONLINE";
    public static final String CHANNEL_POS = "POS";
    public static final String FULFILMENT_PICKUP = "PICKUP";
    public static final String FULFILMENT_DELIVERY = "DELIVERY";
    public static final String FULFILMENT_INSTORE = "INSTORE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_VOIDED = "VOIDED";
    // Set when payment-svc reports a refund (PaymentRefunded) against a sold order.
    public static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String STATUS_REFUNDED = "REFUNDED";
  }

  /** A quantity of one variant to put back into stock — the lines a voided sale restocks. */
  public record RestockLine(UUID variantId, BigDecimal qty) {}

  public record OrderItem(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  /**
   * Append-only record of a manual discount granted on an order (SJ-D6).
   *
   * <p>{@code grantedRole} is the caller role whose ceiling authorised the amount. It is stored
   * rather than looked up later because role assignments change: without it, "was this discount
   * within the grantor's authority at the time?" becomes unanswerable.
   */
  public record OrderDiscount(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      BigDecimal discountPct,
      String reason,
      UUID grantedBy,
      String grantedRole,
      Instant createdAt) {}

  /** Append-only status audit log. */
  public record OrderStatusHistory(
      UUID id,
      UUID tenantId,
      UUID orderId,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  public record Return(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String reason,
      BigDecimal refundAmount,
      String refundMethod,
      String status,
      Instant createdAt,
      Instant completedAt) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String METHOD_ORIGINAL = "ORIGINAL";
    public static final String METHOD_STORE_CREDIT = "STORE_CREDIT";
    public static final String METHOD_GIFT_CARD = "GIFT_CARD";
  }

  public record ReturnItem(
      UUID id,
      UUID tenantId,
      UUID returnId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal refundAmount,
      String condition) {}

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  /** Post-void record; cancels a completed POS transaction before EOD. Append-only. */
  public record PosVoidLog(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String reason,
      UUID voidedBy,
      Instant voidedAt) {}

  // ── Layaway (Gap #14) ─────────────────────────────────────────────────────

  public record Layaway(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      BigDecimal totalAmount,
      BigDecimal depositPaid,
      BigDecimal balance,
      String status,
      String notes,
      Instant createdAt,
      Instant dueDate,
      Instant completedAt,
      Instant cancelledAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  public record LayawayDeposit(
      UUID id,
      UUID tenantId,
      UUID layawayId,
      BigDecimal amount,
      String paymentMethod,
      String reference,
      Instant paidAt) {}

  public record LayawayItem(
      UUID id,
      UUID tenantId,
      UUID layawayId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal) {}

  // ── Gift cards (Gap #14) ──────────────────────────────────────────────────

  public record GiftCard(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String code,
      BigDecimal initialBalance,
      BigDecimal currentBalance,
      String status,
      String currency,
      Instant issuedAt,
      Instant expiresAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPLETED = "DEPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  /** Append-only debit/credit ledger for a gift card. */
  public record GiftCardTransaction(
      UUID id,
      UUID tenantId,
      UUID giftCardId,
      String txType,
      BigDecimal amount,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      UUID orderId,
      String reference,
      Instant createdAt) {
    public static final String TX_ISSUE = "ISSUE";
    public static final String TX_RELOAD = "RELOAD";
    public static final String TX_REDEEM = "REDEEM";
    public static final String TX_REFUND = "REFUND";
    public static final String TX_CANCEL = "CANCEL";
  }

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  public record SpecialOrder(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      java.time.LocalDate requestedDeliveryDate,
      String notes,
      String status,
      BigDecimal subtotal,
      BigDecimal total,
      String currency,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  public record SpecialOrderItem(
      UUID id,
      UUID tenantId,
      UUID soId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  /** Append-only status audit for special orders. */
  public record SpecialOrderStatusHistory(
      UUID id,
      UUID tenantId,
      UUID soId,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  // ── Gap #43: POSLog entry (append-only) ───────────────────────────────────

  /**
   * One line of the staff exception report: everything one cashier (or one store) did over the
   * period that loss prevention cares about, with the sales count that makes it a rate rather than
   * a ranking of who worked hardest.
   *
   * <p>{@code sales} and {@code salesValue} come from the POS transaction journal. They are zero
   * when nothing journalled the sale, which is not the same as "this person made no sales" — the
   * report says so rather than dividing by it.
   */
  public record ExceptionRow(
      String groupKey,
      long discounts,
      java.math.BigDecimal discountAmount,
      long voids,
      long noSales,
      long sales,
      java.math.BigDecimal salesValue) {}

  /** How the exception report buckets its rows. */
  public enum ExceptionGrouping {
    ACTOR,
    STORE
  }

  /**
   * One hour of the trading day.
   *
   * <p>Hours with no trade are absent from the report rather than present as zeroes: a row of
   * zeroes asserts the shop was open and nobody came, which is a different fact from the shop being
   * shut, and only the caller knows which.
   *
   * @param hourOfDay 0-23 on the clock of the timezone the report was asked for, not UTC
   * @param orders how many revenue orders fell in this hour
   * @param grossAmount their total, after any discount
   * @param discountAmount how much was discounted away inside it
   * @param averageBasket gross divided by orders — null is impossible here, since an hour with no
   *     orders produces no row at all
   */
  public record SalesByHourRow(
      int hourOfDay,
      long orders,
      java.math.BigDecimal grossAmount,
      java.math.BigDecimal discountAmount,
      java.math.BigDecimal averageBasket) {}

  /**
   * One member of staff's takings.
   *
   * <p>Sourced from the POS transaction journal, so it covers in-store sales only — an online order
   * has no cashier. {@code UNATTRIBUTED} buckets journal entries that name nobody rather than
   * dropping them.
   *
   * @param groupKey the cashier's user id, or UNATTRIBUTED
   * @param sales how many sales they journalled
   * @param grossAmount what those sales came to
   * @param discountAmount how much they discounted away
   * @param averageBasket gross divided by sales
   * @param discountRate discount as a percentage of gross plus discount — what the sale would have
   *     been worth undiscounted. Null when there is nothing to take a percentage of.
   */
  public record SalesByStaffRow(
      String groupKey,
      long sales,
      java.math.BigDecimal grossAmount,
      java.math.BigDecimal discountAmount,
      java.math.BigDecimal averageBasket,
      java.math.BigDecimal discountRate) {}

  public record PosLogEntry(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      UUID cashierId,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      boolean taxExempt,
      String exemptReason,
      Instant transactionTs,
      Instant createdAt) {}

  // ── Gap #44: Receipt log (append-only) ───────────────────────────────────

  public record OrderReceipt(
      UUID id,
      UUID tenantId,
      UUID orderId,
      String receiptType,
      String emailedTo,
      int printCount,
      Instant generatedAt) {
    public static final String TYPE_PRINT = "PRINT";
    public static final String TYPE_EMAIL = "EMAIL";
  }

  // ── Gap #50: SIM ↔ POS sync — local stock projection ─────────────────────

  /** Local on-hand projection maintained from inventory-svc events. Read-only for POS screens. */
  public record PosStockPosition(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      java.math.BigDecimal onHandQty,
      Instant updatedAt) {}
}
