package com.shelfj.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for notification-svc — the wire contract for the alert, feed and send
 * endpoints.
 *
 * <p>Ids are carried as {@code String} rather than {@code UUID} so the JSON contract stays stable.
 */
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

  @Schema(
      name = "SendNotificationRequest",
      description = "Staff or service-to-service request to deliver one notification.")
  public record SendNotificationRequest(
      @Schema(description = "Email (or other channel address) to deliver to.", required = true)
          @jakarta.validation.constraints.NotBlank
          String recipient,
      @Schema(description = "Subject line.", required = true)
          @jakarta.validation.constraints.NotBlank
          String subject,
      @Schema(description = "Plain-text body.", required = true)
          @jakarta.validation.constraints.NotBlank
          String body,
      @Schema(
              description =
                  "Notification kind recorded in the log, e.g. POS_RECEIPT, ORDER_CONFIRMATION.")
          String type,
      @Schema(
              description =
                  "Optional idempotency id. When set, re-sends with the same (eventId, type) are"
                      + " no-ops. When omitted a new UUID is minted (always delivers).")
          String eventId,
      @Schema(
              description =
                  "The shop's customer this message is about, when there is one. Recorded so that"
                      + " erasing the customer erases the message too (SJ-D43).")
          @jakarta.validation.constraints.Pattern(
              regexp = "^[0-9a-fA-F-]{36}$",
              message = "customerId must be a UUID")
          String customerId) {}

  @Schema(name = "SendNotificationResponse")
  public record SendNotificationResponse(
      @Schema(description = "Event id used for dedupe.") String eventId,
      String type,
      @Schema(description = "SENT when delivered or already delivered.") String status) {}
}
