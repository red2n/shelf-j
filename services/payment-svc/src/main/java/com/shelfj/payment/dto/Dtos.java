package com.shelfj.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
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
}
