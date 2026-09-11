package com.shelfj.notification.messaging;

import com.shelfj.notification.service.Notifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells every store whose stock a recall took off sale. One event names several stores, and {@link
 * Notifier} dedupes on an event id, so each store's message is keyed by an id derived from the
 * event and the store: a redelivery tells nobody twice, and one store's failed send does not stop
 * the others being retried. A malformed payload is skipped.
 *
 * <p>Expected payload: {@code {eventId, tenantId, reference, kind, hazard, storeIds[]}}.
 */
@ApplicationScoped
class RecallOpenedHandler {

  private static final Logger LOG = System.getLogger(RecallOpenedHandler.class.getName());
  static final String NOTIFICATION_TYPE = "RECALL_OPENED";

  private static final Map<String, String> HAZARD_TEXT =
      Map.of(
          "MICROBIOLOGICAL", "microbiological contamination",
          "ALLERGEN", "undeclared allergen",
          "FOREIGN_BODY", "foreign body",
          "CHEMICAL", "chemical contamination",
          "LABELLING", "labelling error",
          "QUALITY", "quality defect");

  @Inject Notifier notifier;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String reference;
    boolean recall;
    String hazard;
    List<UUID> stores;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      reference = obj.getString("reference");
      recall = "RECALL".equals(obj.getString("kind"));
      hazard = hazardText(obj.getString("hazard"));
      stores =
          obj.getJsonArray("storeIds").getValuesAs(JsonString.class).stream()
              .map(s -> UUID.fromString(s.getString()))
              .toList();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallOpened payload skipped: " + e.getMessage());
      return;
    }
    String what = recall ? "Product recall" : "Product withdrawal";
    for (UUID store : stores) {
      notifier.notifyOnce(
          perStore(eventId, store),
          NOTIFICATION_TYPE,
          tenantId,
          null,
          store.toString(),
          what + " " + reference + ": stock taken off sale",
          what
              + " "
              + reference
              + " ("
              + hazard
              + ") has taken stock at this store off sale. Pull it from the shelves and record"
              + " what you found on the Recalls screen"
              + (recall ? ", and display the recall notice at the tills." : "."));
    }
  }

  static UUID perStore(UUID eventId, UUID storeId) {
    return UUID.nameUUIDFromBytes((eventId + ":" + storeId).getBytes(StandardCharsets.UTF_8));
  }

  private static String hazardText(String hazard) {
    return HAZARD_TEXT.getOrDefault(hazard, "safety issue");
  }
}
