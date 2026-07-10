package com.shelfj.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Domain {

  private Domain() {}

  public record ShortageAlert(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal available,
      BigDecimal threshold,
      UUID eventId,
      Instant alertedAt) {}

  /** One delivered outbound notification (N1). */
  public record NotificationLog(
      UUID id,
      UUID tenantId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status,
      Instant createdAt) {}
}
