package com.shelfj.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Request/response DTOs for inventory-svc. No tenant_id in requests — it comes from context. */
public final class Dtos {

  private Dtos() {}

  public record ReceiveRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal qty,
      String batchNo,
      BigDecimal costPrice,
      String expiryDate) {} // expiryDate ISO yyyy-MM-dd

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

  public record LevelResponse(
      String storeId,
      String variantId,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  public record ReservationResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal qty,
      String status,
      String expiresAt) {}

  public record BatchResponse(
      String id,
      String storeId,
      String variantId,
      BigDecimal receivedQty,
      BigDecimal remainingQty) {}
}
