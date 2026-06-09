package com.shelfj.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Request/response DTOs for inventory-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  public record ReceiveRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      String expiryDate) {}

  public record AdjustRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull BigDecimal delta,
      String reason) {}

  public record ReserveRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      String orderId,
      Long ttlSeconds) {}

  public record ThresholdRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal threshold) {}

  public record MaterialStatusRequest(@NotBlank String materialStatus, String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  public record LevelResponse(
      String storeId,
      String variantId,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  public record BatchResponse(
      String id,
      String storeId,
      String variantId,
      String batchNo,
      BigDecimal receivedQty,
      BigDecimal remainingQty,
      BigDecimal costPrice,
      String expiryDate,
      String createdAt,
      String status,
      String materialStatus,
      String materialStatusReason) {}

  public record ReservationResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal qty,
      String orderId,
      String status,
      String expiresAt,
      String createdAt) {}

  public record MovementResponse(
      String id,
      String storeId,
      String variantId,
      String batchId,
      String type,
      BigDecimal qty,
      String refType,
      String refId,
      String createdAt) {}

  public record ThresholdResponse(
      String id, String storeId, String variantId, BigDecimal threshold) {}
}
