package com.shelfj.order.messaging;

import com.shelfj.order.repo.OrderRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gap #50 — SIM→POS direction. Updates the pos_stock_positions projection for each incoming
 * inventory event. Idempotent: the eventId dedupe and the additive upsert commit in one transaction
 * (see {@code upsertStockPositionOnce}). Malformed payloads are skipped; write failures propagate
 * so the consumer loop redelivers instead of losing the event.
 *
 * <p>Delta rules:
 *
 * <ul>
 *   <li>StockReceived → +qty (new stock arrived)
 *   <li>StockDeducted → -qty (stock consumed by sale)
 *   <li>StockAdjusted → +delta (delta is already signed by inventory-svc)
 * </ul>
 */
@ApplicationScoped
class InventoryEventHandler {

  private static final Logger LOG = System.getLogger(InventoryEventHandler.class.getName());
  static final String CONSUMER_NAME = "order-svc/inventory-sync";

  private static final Pattern EVENT_TYPE = Pattern.compile("\"eventType\"\\s*:\\s*\"(\\w+)\"");
  private static final Pattern EVENT_ID =
      Pattern.compile("\"eventId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern TENANT_ID =
      Pattern.compile("\"tenantId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern STORE_ID =
      Pattern.compile("\"storeId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern VARIANT_ID =
      Pattern.compile("\"variantId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern QTY = Pattern.compile("\"qty\"\\s*:\\s*([0-9.]+)");
  private static final Pattern DELTA = Pattern.compile("\"delta\"\\s*:\\s*(-?[0-9.]+)");

  @Inject OrderRepository repo;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID variantId;
    BigDecimal delta;
    try {
      String eventType = extract(EVENT_TYPE, json);
      String eventIdStr = extract(EVENT_ID, json);
      String tenantIdStr = extract(TENANT_ID, json);
      String storeIdStr = extract(STORE_ID, json);
      String variantIdStr = extract(VARIANT_ID, json);
      if (eventIdStr == null || tenantIdStr == null || storeIdStr == null || variantIdStr == null) {
        return;
      }

      eventId = UUID.fromString(eventIdStr);
      tenantId = UUID.fromString(tenantIdStr);
      storeId = UUID.fromString(storeIdStr);
      variantId = UUID.fromString(variantIdStr);

      if ("StockReceived".equals(eventType)) {
        String qtyStr = extract(QTY, json);
        if (qtyStr == null) return;
        delta = new BigDecimal(qtyStr);
      } else if ("StockDeducted".equals(eventType)) {
        String qtyStr = extract(QTY, json);
        if (qtyStr == null) return;
        delta = new BigDecimal(qtyStr).negate();
      } else if ("StockAdjusted".equals(eventType)) {
        String deltaStr = extract(DELTA, json);
        if (deltaStr == null) return;
        delta = new BigDecimal(deltaStr);
      } else {
        return;
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed inventory event skipped: " + e.getMessage());
      return;
    }

    boolean processed =
        repo.upsertStockPositionOnce(eventId, CONSUMER_NAME, tenantId, storeId, variantId, delta);
    if (processed) {
      LOG.log(Level.DEBUG, "StockPosition updated {0}/{1} delta={2}", storeId, variantId, delta);
    }
  }

  private static String extract(Pattern p, String s) {
    if (s == null) return null;
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}
