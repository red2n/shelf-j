package com.shelfj.notification.messaging;

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
 * Alerts a store that a food-safety check failed: a chiller read warm, a hot cabinet read cold, or
 * a checklist was marked failed. Addressed to the store's devices, like a shortage alert, because
 * whoever is on shift has to act on it rather than whoever took the reading. Idempotent through
 * {@link Notifier}'s (eventId, type) dedupe; a malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, pointName, checkTypeCode, kind, value,
 * unit, minValue, maxValue}}.
 */
@ApplicationScoped
class FoodSafetyCheckFailedHandler {

  private static final Logger LOG = System.getLogger(FoodSafetyCheckFailedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "FOOD_SAFETY_CHECK_FAILED";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String pointName;
    BigDecimal value;
    BigDecimal min;
    BigDecimal max;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      pointName = obj.getString("pointName");
      value = decimal(obj, "value");
      min = decimal(obj, "minValue");
      max = decimal(obj, "maxValue");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed FoodSafetyCheckFailed payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        "Food safety check failed: " + pointName,
        describe(pointName, value, min, max));
  }

  /** What failed, in words a member of staff can act on without opening the screen first. */
  static String describe(String pointName, BigDecimal value, BigDecimal min, BigDecimal max) {
    String what =
        value == null
            ? pointName + " was recorded as failed."
            : pointName + " read " + value.toPlainString() + " °C against " + limit(min, max) + ".";
    return what + " Record what was done about it on the Food safety screen.";
  }

  private static String limit(BigDecimal min, BigDecimal max) {
    if (min != null && max != null) {
      return "limits of " + min.toPlainString() + " °C to " + max.toPlainString() + " °C";
    }
    return min != null
        ? "a limit of at least " + min.toPlainString() + " °C"
        : "a limit of at most " + max.toPlainString() + " °C";
  }

  /**
   * A null field is written as null in the payload, but an absent one must read the same (SJ-D14).
   */
  private static BigDecimal decimal(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key)
        ? obj.getJsonNumber(key).bigDecimalValue()
        : null;
  }
}
