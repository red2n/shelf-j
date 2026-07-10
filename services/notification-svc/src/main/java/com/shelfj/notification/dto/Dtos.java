package com.shelfj.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Dtos {

  private Dtos() {}

  public record ShortageAlertDto(
      String id,
      String tenantId,
      String storeId,
      String variantId,
      BigDecimal available,
      BigDecimal threshold,
      String eventId,
      Instant alertedAt) {}

  public record ShortageAlertListResponse(List<ShortageAlertDto> data) {}

  /** One in-app notification for the notifications feed. */
  public record NotificationDto(
      String id,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status,
      Instant createdAt) {}
}
