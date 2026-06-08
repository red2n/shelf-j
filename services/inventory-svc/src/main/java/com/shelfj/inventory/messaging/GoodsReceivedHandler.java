package com.shelfj.inventory.messaging;

import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.shelfj.inventory.repo.InventoryRepository;
import com.shelfj.inventory.service.InventoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

/**
 * Business handler for {@code shelfj.purchase.goods-received} events.
 * Creates inventory batches from each line in the GRN payload, idempotently
 * (dedupe on eventId). Separated from {@link GoodsReceivedConsumer} so Kafka
 * lifecycle and domain logic each have a single reason to change (SRP).
 *
 * <p>Expected payload:
 * {@code {eventId, tenantId, storeId, refId?, lines:[{variantId, qty, batchNo?, costPrice?, expiryDate?}]}}.</p>
 */
@ApplicationScoped
class GoodsReceivedHandler {

    private static final Logger LOG = System.getLogger(GoodsReceivedHandler.class.getName());
    static final String CONSUMER_NAME = "inventory-svc/goods-received";

    @Inject InventoryService service;
    @Inject InventoryRepository repo;

    void handle(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            JsonObject obj    = reader.readObject();
            UUID eventId      = UUID.fromString(obj.getString("eventId"));

            if (!repo.markProcessedIfNew(eventId, CONSUMER_NAME)) {
                return;
            }
            UUID tenantId = UUID.fromString(obj.getString("tenantId"));
            UUID storeId  = UUID.fromString(obj.getString("storeId"));
            UUID refId    = obj.containsKey("refId") && !obj.isNull("refId")
                    ? UUID.fromString(obj.getString("refId")) : null;

            JsonArray lines = obj.getJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                JsonObject line   = lines.getJsonObject(i);
                UUID variantId    = UUID.fromString(line.getString("variantId"));
                BigDecimal qty    = new BigDecimal(line.get("qty").toString());
                String batchNo    = nullableString(line, "batchNo");
                BigDecimal cost   = line.containsKey("costPrice") && !line.isNull("costPrice")
                        ? new BigDecimal(line.get("costPrice").toString()) : null;
                LocalDate expiry  = line.containsKey("expiryDate") && !line.isNull("expiryDate")
                        ? LocalDate.parse(line.getString("expiryDate")) : null;
                service.receive(tenantId, storeId, variantId, qty, batchNo, cost, expiry, "GRN", refId);
            }
            LOG.log(Level.INFO, "GoodsReceived {0}: created {1} batch(es)", eventId, lines.size());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to handle GoodsReceived: " + e.getMessage());
        }
    }

    private static String nullableString(JsonObject obj, String key) {
        return obj.containsKey(key) && !obj.isNull(key) ? obj.getString(key) : null;
    }
}
