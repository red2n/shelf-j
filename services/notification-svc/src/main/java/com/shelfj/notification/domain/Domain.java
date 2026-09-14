package com.shelfj.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain records for notification-svc — alerts raised and messages delivered.
 *
 * <p>These never cross the HTTP boundary: {@link com.shelfj.notification.mapper.Mappers} converts
 * them to the DTOs in {@link com.shelfj.notification.dto.Dtos} first.
 */
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
  /**
   * The channels a message can go out on (13.7). EMAIL is whatever the deployment's default channel
   * is.
   */
  public static final class Channel {
    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";
    public static final String PUSH = "PUSH";
    public static final String APP = "APP";
    public static final java.util.List<String> ALL = java.util.List.of(EMAIL, SMS, PUSH, APP);

    private Channel() {}
  }

  /** A device a login registered for push (13.7). */
  public record PushDevice(
      UUID id,
      UUID tenantId,
      UUID userId,
      String platform,
      String token,
      Instant registeredAt,
      Instant lastSeenAt) {
    public static final java.util.List<String> PLATFORMS =
        java.util.List.of("ANDROID", "IOS", "WEB");
  }

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
