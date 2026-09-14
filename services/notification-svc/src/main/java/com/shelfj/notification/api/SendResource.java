package com.shelfj.notification.api;

import com.shelfj.ids.Ids;
import com.shelfj.notification.channel.PushChannel;
import com.shelfj.notification.channel.SmsChannel;
import com.shelfj.notification.domain.Domain.Channel;
import com.shelfj.notification.dto.Dtos.SendNotificationRequest;
import com.shelfj.notification.dto.Dtos.SendNotificationResponse;
import com.shelfj.notification.provider.ProviderException;
import com.shelfj.notification.service.Notifier;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Staff-triggered notification send (POS email receipt, manual resend, etc.). Not under {@code
 * /admin/} so cashiers (staff but not management) can call it; {@link
 * com.shelfj.web.AdminAuthorizationFilter} still requires any staff role on POST.
 * Service-to-service callers (order-svc) forward the cashier's identity headers.
 */
@Path("/notifications")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Send")
public class SendResource {

  /** PECR reg.22: anything promoting the business, as opposed to a message about an order. */
  private static final String CATEGORY_MARKETING = "MARKETING";

  @Inject Notifier notifier;
  @Inject com.shelfj.notification.client.MarketingConsentClient consent;
  @Inject com.shelfj.notification.channel.Channels channels;
  @Inject com.shelfj.notification.service.NotificationService service;
  @Inject TenantContext ctx;

  /**
   * Sends one notification through the configured channel.
   *
   * <p>Idempotent on {@code (eventId, type)}: supply a stable {@code eventId} and a retry will not
   * deliver a second copy. Omitting it mints a fresh id, which makes the call effectively
   * non-idempotent.
   *
   * @param req the recipient, subject, body and optional {@code eventId}/{@code type}
   * @return {@code 202} whether the message was sent now or already delivered earlier
   * @throws com.shelfj.web.ApiException {@code 403} when the caller holds no staff role
   */
  @Operation(
      summary = "Send a notification",
      description =
          "Delivers one notification on the named channel: EMAIL (the configured default: SMTP,"
              + " MQTT or in-app), SMS to an E.164 number, PUSH to every device the recipient"
              + " login registered here, or APP (13.7). Idempotent on (eventId, type) when eventId"
              + " is supplied. Requires a staff role.")
  @APIResponse(responseCode = "202", description = "Accepted (sent or already delivered)")
  @APIResponse(
      responseCode = "400",
      description =
          "Validation failed: an unknown channel, an SMS recipient that is not E.164, an SMS body"
              + " over 1600 characters, a PUSH recipient that is not a login id")
  @APIResponse(responseCode = "403", description = "Caller is not staff")
  @APIResponse(
      responseCode = "409",
      description =
          "A marketing message with no recorded consent, or with consent that could not be"
              + " checked, or on a channel consent cannot cover; a push to a login with no device"
              + " — nothing is sent")
  @APIResponse(responseCode = "503", description = "The channel's provider is not configured")
  @POST
  @Path("/send")
  public Response send(SendNotificationRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID eventId =
        req.eventId() != null && !req.eventId().isBlank()
            ? UUID.fromString(req.eventId())
            : Ids.newId();
    String type = req.type() == null || req.type().isBlank() ? "MANUAL" : req.type().trim();
    UUID customerId =
        req.customerId() != null && !req.customerId().isBlank()
            ? UUID.fromString(req.customerId())
            : null;
    String body = req.body();
    String channelName =
        req.channel() == null || req.channel().isBlank()
            ? Channel.EMAIL
            : req.channel().trim().toUpperCase(Locale.ROOT);
    var channel = channels.forName(channelName);
    if (channel == null) {
      throw ApiException.badRequest(
          "CHANNEL_UNKNOWN",
          "channel is one of " + String.join(", ", Channel.ALL) + ", not " + req.channel());
    }
    if (Channel.SMS.equals(channelName)) {
      if (!SmsChannel.E164.matcher(req.recipient().trim()).matches()) {
        throw ApiException.badRequest(
            "SMS_RECIPIENT_INVALID", "an SMS recipient is an E.164 number, like +447700900123");
      }
      if (body.length() > SmsChannel.MAX_BODY) {
        throw ApiException.badRequest(
            "SMS_BODY_TOO_LONG", "an SMS body is at most " + SmsChannel.MAX_BODY + " characters");
      }
      var provider = channels.sms().provider();
      if (provider == null || !provider.isConfigured()) {
        throw new ApiException(
            503, "CHANNEL_UNAVAILABLE", "no SMS provider is configured", java.util.List.of());
      }
    }
    if (Channel.PUSH.equals(channelName)) {
      UUID login = Parsing.uuid(req.recipient().trim(), "recipient");
      var provider = channels.push().provider();
      if (provider == null || !provider.isConfigured()) {
        throw new ApiException(
            503, "CHANNEL_UNAVAILABLE", "no push provider is configured", java.util.List.of());
      }
      if (!service.hasDevices(tenantId, login)) {
        throw ApiException.conflict("PUSH_NO_DEVICE", "that login has no device registered here");
      }
    }
    if (CATEGORY_MARKETING.equalsIgnoreCase(req.category())) {
      // PECR reg.22/23. Consent belongs to a person, not to an address, so a marketing send that
      // names no customer cannot be lawful whatever the shop believes about the recipient.
      if (customerId == null) {
        throw ApiException.conflict(
            "MARKETING_CONSENT_MISSING",
            "a marketing message must name the customer whose consent permits it");
      }
      // Consent is recorded per channel, and only for the channels the regulation names. A push
      // or an in-app notice has no consent record to stand on, so marketing cannot go that way.
      if (!Channel.EMAIL.equals(channelName) && !Channel.SMS.equals(channelName)) {
        throw ApiException.conflict(
            "MARKETING_CHANNEL_UNSUPPORTED",
            "marketing goes by EMAIL or SMS, where consent is recorded; not by " + channelName);
      }
      var allowance = consent.allowance(tenantId, customerId, channelName);
      if (!allowance.allowed()) {
        throw ApiException.conflict(
            "MARKETING_CONSENT_MISSING",
            "this customer may not be sent marketing: " + allowance.reason());
      }
      // Every marketing message carries its own way out (reg.23). Appended here rather than left
      // to each caller, so a caller cannot forget the part that makes the send lawful.
      body = body + unsubscribeFooter(allowance.unsubscribeToken());
    }
    try {
      notifier.notifyOnce(
          eventId,
          type,
          tenantId,
          customerId,
          req.recipient().trim(),
          req.subject(),
          body,
          channelName);
    } catch (PushChannel.NoDeviceException e) {
      throw new ApiException(
          409,
          "PUSH_NO_DEVICE",
          "that login has no device registered here",
          java.util.List.of(),
          e);
    } catch (ProviderException e) {
      throw new ApiException(
          e.retryable() ? 503 : 409, e.code(), e.getMessage(), java.util.List.of(), e);
    }
    return Response.accepted()
        .entity(
            ApiResponse.ok(
                new SendNotificationResponse(eventId.toString(), type, "SENT", channelName)))
        .build();
  }

  /**
   * The opt-out line appended to every marketing message (PECR reg.23).
   *
   * @param token the opt-out token minted for this send
   * @return the footer, as plain text
   */
  private static String unsubscribeFooter(String token) {
    return "\n\n---\nTo stop receiving marketing from us, use this link:"
        + " /marketing/unsubscribe?token="
        + token;
  }
}
