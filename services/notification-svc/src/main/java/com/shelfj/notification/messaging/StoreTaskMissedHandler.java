package com.shelfj.notification.messaging;

import com.shelfj.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.UUID;

/**
 * Tells a store that a task fell due and was never done (store operations & workforce).
 *
 * <p>Addressed to the store's devices, like a shortage or a failed check, because whoever is on
 * shift is the one who can still do it — and because the missed closing check is the one a manager
 * has to hear about tonight, not read on a report next week. Idempotent through {@link Notifier}'s
 * (eventId, type) dedupe: the sweep announces each miss once, and a redelivery must not nag.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, title, kind, businessDate, dueAt,
 * required}}.
 */
@ApplicationScoped
class StoreTaskMissedHandler {

  private static final Logger LOG = System.getLogger(StoreTaskMissedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "STORE_TASK_MISSED";

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    String title;
    String kind;
    String businessDate;
    boolean required;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      title = obj.getString("title");
      kind = obj.getString("kind", "");
      businessDate = obj.getString("businessDate");
      required = obj.getBoolean("required", true);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StoreTaskMissed payload skipped: " + e.getMessage());
      return;
    }
    notifier.notifyOnce(
        eventId,
        NOTIFICATION_TYPE,
        tenantId,
        null,
        storeId.toString(),
        "Not done: " + title,
        describe(title, kind, businessDate, required));
  }

  /** What was missed, in words whoever is on shift can act on. */
  static String describe(String title, String kind, String businessDate, boolean required) {
    String when =
        switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
          case "OPENING" -> "The opening list";
          case "CLOSING" -> "The closing list";
          default -> "The task";
        };
    return when
        + " \""
        + title
        + "\" for "
        + businessDate
        + " fell due and was not done"
        + (required ? ", and it is required" : "")
        + ". Do it now if it still can be, or record why it was skipped.";
  }
}
