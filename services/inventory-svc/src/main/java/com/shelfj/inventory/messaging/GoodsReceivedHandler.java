package com.shelfj.inventory.messaging;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.service.InventoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.purchase.goods-received} events. Creates inventory batches
 * from each line in the GRN payload, idempotently. Separated from {@link GoodsReceivedConsumer} so
 * Kafka lifecycle and domain logic each have a single reason to change (SRP).
 *
 * <p>Each line is deduped on a deterministic per-line id derived from the eventId, INSIDE the
 * line's transaction: a redelivered event skips lines that already committed and retries only the
 * rest. Malformed payloads are skipped; write failures propagate so the consumer loop redelivers.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, refId?, lines:[{variantId, qty,
 * batchNo?, costPrice?, expiryDate?}]}}.
 */
@ApplicationScoped
class GoodsReceivedHandler {

  private static final Logger LOG = System.getLogger(GoodsReceivedHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/goods-received";

  @Inject InventoryService service;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID refId;
    JsonArray lines;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      refId =
          obj.containsKey("refId") && !obj.isNull("refId")
              ? UUID.fromString(obj.getString("refId"))
              : null;
      lines = obj.getJsonArray("lines");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed GoodsReceived payload skipped: " + e.getMessage());
      return;
    }
    if (lines == null) {
      return;
    }

    int created = 0;
    for (int i = 0; i < lines.size(); i++) {
      JsonObject line = lines.getJsonObject(i);
      UUID variantId = UUID.fromString(line.getString("variantId"));
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      String batchNo = nullableString(line, "batchNo");
      BigDecimal cost =
          line.containsKey("costPrice") && !line.isNull("costPrice")
              ? new BigDecimal(line.get("costPrice").toString())
              : null;
      LocalDate expiry =
          line.containsKey("expiryDate") && !line.isNull("expiryDate")
              ? LocalDate.parse(line.getString("expiryDate"))
              : null;
      if (service.receiveOnce(
          lineDedupeId(eventId, i),
          CONSUMER_NAME,
          tenantId,
          storeId,
          variantId,
          qty,
          batchNo,
          cost,
          expiry,
          "GRN",
          refId)) {
        created++;
      }
    }
    if (created > 0) {
      LOG.log(Level.INFO, "GoodsReceived {0}: created {1} batch(es)", eventId, created);
    }
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return Ids.derived(eventId, CONSUMER_NAME + ":" + lineIndex);
  }

  private static String nullableString(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key) : null;
  }
}
