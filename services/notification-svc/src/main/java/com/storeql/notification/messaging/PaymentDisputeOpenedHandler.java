package com.storeql.notification.messaging;

import com.storeql.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Tells a business a card payment has been charged back (11.9), and by when it has to answer: a
 * dispute nobody answers is a dispute lost, and the date is the bank's, not ours. {@link Notifier}
 * dedupes redeliveries, so a business is told once per dispute. A malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, disputeId, orderId, storeId?, amount, currency,
 * reason, evidenceDueBy?}}.
 */
@ApplicationScoped
class PaymentDisputeOpenedHandler {

  private static final Logger LOG = System.getLogger(PaymentDisputeOpenedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "PAYMENT_DISPUTE_OPENED";

  /** The event carries UTC and a message has no viewer's zone to convert to, so it says so. */
  private static final DateTimeFormatter DUE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String storeId;
    BigDecimal amount;
    String currency;
    String reason;
    Instant dueBy;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"PaymentDisputeOpened".equals(obj.getString("eventType", ""))) return;
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = obj.getString("storeId", null);
      amount = obj.getJsonNumber("amount").bigDecimalValue();
      currency = obj.getString("currency");
      reason = obj.getString("reason", "GENERAL");
      dueBy =
          obj.containsKey("evidenceDueBy") ? Instant.parse(obj.getString("evidenceDueBy")) : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed PaymentDisputeOpened payload skipped: " + e.getMessage());
      return;
    }
    String sum = amount.stripTrailingZeros().toPlainString() + " " + currency;
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        // A store's alert where the payment was taken at one; the business's own otherwise.
        storeId == null || storeId.isBlank() ? tenantId.toString() : storeId,
        "Chargeback: " + sum + " disputed",
        "A cardholder's bank has disputed a card payment of "
            + sum
            + " ("
            + reason.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
            + "). "
            + (dueBy == null
                ? "Answer it on the Disputes screen as soon as you can."
                : "Answer it on the Disputes screen by "
                    + DUE.format(dueBy)
                    + ": after that it is lost."));
  }
}
