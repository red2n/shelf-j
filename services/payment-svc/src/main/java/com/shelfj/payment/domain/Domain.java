package com.shelfj.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Domain {

  private Domain() {}

  public record PaymentTender(
      UUID id,
      UUID tenantId,
      UUID orderId,
      BigDecimal amount,
      String method,
      String reference,
      String idempotencyKey,
      String status,
      String notes,
      Instant createdAt) {

    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_CARD = "CARD";
    public static final String METHOD_GIFT_CARD = "GIFT_CARD";
    public static final String METHOD_VOUCHER = "VOUCHER";

    public static final String STATUS_CAPTURED = "CAPTURED";
    public static final String STATUS_FAILED = "FAILED";
  }

  public record RefundTender(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID paymentId,
      BigDecimal amount,
      String method,
      String reference,
      String idempotencyKey,
      String reason,
      Instant createdAt) {}
}
