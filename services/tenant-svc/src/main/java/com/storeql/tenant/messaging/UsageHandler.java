package com.storeql.tenant.messaging;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.service.UsageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Turns one event into one usage record (21.10), once: an order is counted by its order id, so the
 * till's offline replay of the same sale is the same order; a text by the event that announced it.
 * A malformed payload is skipped with a warning; a write failure propagates so the loop redelivers.
 */
@ApplicationScoped
class UsageHandler {

  private static final System.Logger LOG = System.getLogger(UsageHandler.class.getName());

  /** A text is at most ten parts (notification-svc refuses a longer body); more is not a text. */
  static final long MAX_SMS_PARTS = 10;

  @Inject UsageService usage;

  /** One order placed, online or at the till. */
  boolean orderPlaced(String json) {
    UUID tenantId;
    UUID orderId;
    try {
      JsonObject e = read(json);
      tenantId = Ids.parse(e.getString("tenantId"));
      orderId = Ids.parse(e.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderPlaced skipped by the meter: " + e.getMessage());
      return false;
    }
    // Outside the parse: a write that fails must reach the loop and be redelivered, not be taken
    // for a malformed event and the order go uncounted.
    return usage.record(tenantId, Meters.ORDERS, 1, "order:" + orderId).fresh();
  }

  /** One text sent, counted by the parts the carrier charges for. */
  boolean smsSent(String json) {
    UUID tenantId;
    UUID eventId;
    long parts;
    try {
      JsonObject e = read(json);
      tenantId = Ids.parse(e.getString("tenantId"));
      eventId = Ids.parse(e.getString("eventId"));
      parts = e.getJsonNumber("parts").longValueExact();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed SmsSent skipped by the meter: " + e.getMessage());
      return false;
    }
    if (parts < 1 || parts > MAX_SMS_PARTS) {
      LOG.log(Level.WARNING, "SmsSent {0} claims {1} parts; skipped", eventId, parts);
      return false;
    }
    return usage.record(tenantId, Meters.SMS, parts, "sms:" + eventId).fresh();
  }

  private static JsonObject read(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      return reader.readObject();
    }
  }
}
