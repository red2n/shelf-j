package com.shelfj.notification.messaging;

import com.shelfj.notification.client.CustomerClient;
import com.shelfj.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emails the buyer an order confirmation when an order is confirmed. Guest orders (no customerId)
 * are skipped; for a real customer the email is resolved from customer-svc (best-effort — a missing
 * email just skips the send). Malformed payloads are skipped; a delivery failure propagates so the
 * consumer loop retries (idempotent per event in {@link Notifier}).
 */
@ApplicationScoped
class OrderConfirmedHandler {

  private static final Logger LOG = System.getLogger(OrderConfirmedHandler.class.getName());

  @Inject Notifier notifier;
  @Inject CustomerClient customers;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    UUID customerId;
    BigDecimal total;
    String currency;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!obj.containsKey("customerId") || obj.isNull("customerId")) {
        return; // guest checkout — no account to email
      }
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      orderId = UUID.fromString(obj.getString("orderId"));
      customerId = UUID.fromString(obj.getString("customerId"));
      total = obj.getJsonNumber("total").bigDecimalValue();
      currency = obj.getString("currency", "GBP");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderConfirmed payload skipped: " + e.getMessage());
      return;
    }

    String email = customers.emailOf(tenantId, customerId).orElse(null);
    if (email == null) {
      LOG.log(Level.DEBUG, "No email for customer {0} — order confirmation skipped", customerId);
      return;
    }
    String body =
        "Thanks for your order!\n\nOrder "
            + orderId
            + "\nTotal: "
            + currency
            + " "
            + total.toPlainString()
            + "\n\n— Shelf-J";
    notifier.notifyOnce(
        eventId,
        "ORDER_CONFIRMATION",
        tenantId,
        customerId,
        email,
        "Your order is confirmed",
        body);
  }
}
