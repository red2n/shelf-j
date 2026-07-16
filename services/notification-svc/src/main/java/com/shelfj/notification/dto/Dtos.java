package com.shelfj.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class Dtos {

  private Dtos() {}

  @Schema(
      name = "ShortageAlertDto",
      description = "A recorded stock-shortage alert, sourced from a StockBelowThreshold event.")
  public record ShortageAlertDto(
      String id,
      String tenantId,
      @Schema(description = "UUID of the store where the shortage occurred.") String storeId,
      @Schema(description = "UUID of the product variant that is short.") String variantId,
      @Schema(description = "Quantity available at the time of the alert.") BigDecimal available,
      @Schema(description = "Configured shortage threshold that was breached.")
          BigDecimal threshold,
      @Schema(description = "Id of the source StockBelowThreshold event; used for dedup.")
          String eventId,
      Instant alertedAt) {}

  @Schema(name = "ShortageAlertListResponse")
  public record ShortageAlertListResponse(List<ShortageAlertDto> data) {}

  /** One in-app notification for the notifications feed. */
  @Schema(name = "NotificationDto", description = "A single in-app notification feed entry.")
  public record NotificationDto(
      String id,
      @Schema(description = "Notification kind, e.g. WELCOME, ORDER_CONFIRMATION, SHORTAGE_ALERT.")
          String type,
      @Schema(description = "Delivery channel used, e.g. APP or SMTP.") String channel,
      @Schema(description = "Recipient identifier (email or user id) the notification was sent to.")
          String recipient,
      String subject,
      String body,
      @Schema(description = "Delivery status, e.g. SENT, FAILED.") String status,
      Instant createdAt) {}
}
