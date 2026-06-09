package com.shelfj.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain records for order-svc. Money is BigDecimal; times are UTC Instant. */
public final class Domain {

  private Domain() {}

  // ── Core order ────────────────────────────────────────────────────────────

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
      Instant updatedAt) {
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
  }

  public record OrderItem(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

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
}
