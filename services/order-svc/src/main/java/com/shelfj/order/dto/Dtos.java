package com.shelfj.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/** Request/response DTOs for order-svc. tenant_id never in request — comes from JWT context. */
public final class Dtos {

  private Dtos() {}

  // ── Order ─────────────────────────────────────────────────────────────────

  public record OrderItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice,
      String notes) {}

  public record PlaceOrderRequest(
      @NotBlank String storeId,
      String customerId,
      @NotBlank String channel,
      String fulfilmentType,
      @NotNull List<OrderItemRequest> items,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      String currency,
      String notes,
      String idempotencyKey) {}

  public record OrderItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  public record OrderResponse(
      String id,
      String storeId,
      String customerId,
      String channel,
      String fulfilmentType,
      String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      String notes,
      String createdAt,
      String updatedAt,
      List<OrderItemResponse> items) {}

  public record OrderStatusHistoryResponse(
      String id,
      String orderId,
      String fromStatus,
      String toStatus,
      String reason,
      String changedBy,
      String changedAt) {}

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  public record ReturnItemRequest(
      @NotBlank String variantId, @NotNull @Positive BigDecimal qty, String condition) {}

  public record CreateReturnRequest(
      @NotBlank String reason, String refundMethod, @NotNull List<ReturnItemRequest> items) {}

  public record ReturnItemResponse(
      String id, String variantId, BigDecimal qty, BigDecimal refundAmount, String condition) {}

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

  public record VoidRequest(@NotBlank String reason) {}

  public record VoidResponse(String orderId, String reason, String voidedAt) {}

  // ── Layaway (Gap #14) ─────────────────────────────────────────────────────

  public record LayawayItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice) {}

  public record CreateLayawayRequest(
      @NotBlank String storeId,
      String customerId,
      @NotNull List<LayawayItemRequest> items,
      @NotNull @Positive BigDecimal initialDeposit,
      String paymentMethod,
      String dueDate,
      String notes) {}

  public record AddDepositRequest(
      @NotNull @Positive BigDecimal amount, @NotBlank String paymentMethod, String reference) {}

  public record LayawayItemResponse(
      String id, String variantId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

  public record LayawayDepositResponse(
      String id, BigDecimal amount, String paymentMethod, String reference, String paidAt) {}

  public record LayawayResponse(
      String id,
      String storeId,
      String customerId,
      BigDecimal totalAmount,
      BigDecimal depositPaid,
      BigDecimal balance,
      String status,
      String notes,
      String createdAt,
      String dueDate,
      String completedAt,
      String cancelledAt,
      List<LayawayItemResponse> items,
      List<LayawayDepositResponse> deposits) {}

  // ── Gift cards (Gap #14) ──────────────────────────────────────────────────

  public record IssueGiftCardRequest(
      @NotBlank String storeId,
      @NotNull @Positive BigDecimal amount,
      String currency,
      String expiresAt) {}

  public record ReloadGiftCardRequest(@NotNull @Positive BigDecimal amount, String reference) {}

  public record RedeemGiftCardRequest(
      @NotNull @Positive BigDecimal amount, String orderId, String reference) {}

  public record GiftCardResponse(
      String id,
      String storeId,
      String code,
      BigDecimal initialBalance,
      BigDecimal currentBalance,
      String status,
      String currency,
      String issuedAt,
      String expiresAt) {}

  public record GiftCardTransactionResponse(
      String id,
      String txType,
      BigDecimal amount,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      String orderId,
      String reference,
      String createdAt) {}
}
