package com.shelfj.inventory.messaging;

import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gap #50 — POS→SIM direction. Handles OrderFulfilled, OrderReturned and OrderCancelled events from
 * order-svc.
 *
 * <ul>
 *   <li>OrderFulfilled → consume the checkout stock hold for each line that has one (reservation
 *       placed by order-svc at ONLINE checkout); FIFO-deduct directly for lines without a hold (POS
 *       orders, or online orders whose hold expired). (SALE movement either way.)
 *   <li>OrderReturned → receive stock back for each returned line item (RETURN movement).
 *   <li>OrderVoided → receive back what a voided till sale took, net of anything already returned
 *       (RECEIVE movement, reference type VOID). Empty when the sale was never handed over.
 *   <li>OrderCancelled → release every HELD reservation for the order so the stock returns to
 *       availability.
 * </ul>
 *
 * <p>Each fulfil/return line is deduped on a deterministic per-line id INSIDE the line's
 * transaction, so a redelivered event skips lines that already committed and retries only the rest.
 * A 4xx business rejection (e.g. insufficient stock) skips just that line, as before; transient
 * failures propagate so the consumer loop redelivers the event. Cancellation release is naturally
 * idempotent (releasing a non-HELD reservation is a no-op).
 *
 * <p>Expected fulfil/return/void payload shape: {@code {eventId, eventType, tenantId, orderId,
 * storeId, items: [{variantId, qty}]}}. Cancelled payload: {@code {eventType, tenantId, orderId,
 * reason}}.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/order-sync";

  @Inject InventoryService service;

  void handle(String json) {
    JsonObject obj;
    String eventType;
    UUID tenantId;
    UUID orderId;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      eventType = obj.getString("eventType", "");
      tenantId = UUID.fromString(obj.getString("tenantId"));
      orderId = UUID.fromString(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }

    if ("OrderCancelled".equals(eventType)) {
      releaseHolds(tenantId, orderId);
      return;
    }

    boolean fulfil = "OrderFulfilled".equals(eventType);
    boolean returned = "OrderReturned".equals(eventType);
    // SJ-D40: a till sale now deducts stock when it is paid for, so voiding one must put it back.
    // A void that was never handed over carries no lines, and an OrderVoided from before this
    // change has no eventId or storeId and is skipped below as malformed — correctly, because the
    // till sales voided then had never deducted anything.
    boolean voided = "OrderVoided".equals(eventType);
    if (!fulfil && !returned && !voided) {
      return;
    }

    UUID eventId;
    UUID storeId;
    JsonArray items;
    try {
      eventId = UUID.fromString(obj.getString("eventId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      items = obj.getJsonArray("items");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }
    if (items == null || items.isEmpty()) {
      return;
    }

    // The checkout holds for this order, if any. Consumed holds vanish from this list, so on a
    // redelivered event an already-consumed line falls to the deduct path — which the per-line
    // dedupe mark then skips.
    List<Reservation> holds = fulfil ? heldReservationsQuietly(tenantId, orderId) : List.of();

    for (int i = 0; i < items.size(); i++) {
      JsonObject line = items.getJsonObject(i);
      UUID variantId = UUID.fromString(line.getString("variantId"));
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      UUID dedupeId = lineDedupeId(eventId, i);
      try {
        if (fulfil) {
          Reservation hold = takeMatchingHold(holds, variantId, qty);
          if (hold != null) {
            service.consumeOnce(dedupeId, CONSUMER_NAME, tenantId, hold.id());
          } else {
            service.deductSaleFromOrderOnce(
                dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
          }
        } else if (voided) {
          service.receiveVoidFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        } else {
          service.receiveReturnFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        }
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e; // transient — let the consumer loop redeliver; completed lines are deduped
        }
        // business rejection (e.g. insufficient stock) — skip this line, as before
        LOG.log(
            Level.WARNING,
            "{0} line variant {1} skipped: {2}",
            eventType,
            variantId,
            e.getMessage());
      }
    }
    // Defensive: holds that matched no fulfilled line (order edited, qty drift) must not stay
    // HELD forever — release them so the stock returns to availability.
    for (Reservation leftover : holds) {
      releaseQuietly(tenantId, leftover.id());
    }
    LOG.log(Level.INFO, "{0} {1}: processed {2} line(s)", eventType, orderId, items.size());
  }

  /** Removes and returns the first HELD reservation matching this line, or null if none. */
  private static Reservation takeMatchingHold(
      List<Reservation> holds, UUID variantId, BigDecimal qty) {
    for (int i = 0; i < holds.size(); i++) {
      Reservation r = holds.get(i);
      if (r.variantId().equals(variantId) && r.qty().compareTo(qty) == 0) {
        return holds.remove(i);
      }
    }
    return null;
  }

  private List<Reservation> heldReservationsQuietly(UUID tenantId, UUID orderId) {
    try {
      return new ArrayList<>(service.heldReservationsByOrder(tenantId, orderId));
    } catch (RuntimeException e) {
      // Fall back to the plain deduct path — the per-line dedupe still protects correctness;
      // any unconsumed hold is reclaimed by the TTL sweeper.
      LOG.log(Level.WARNING, "hold lookup for order {0} failed: {1}", orderId, e.getMessage());
      return new ArrayList<>();
    }
  }

  private void releaseHolds(UUID tenantId, UUID orderId) {
    List<Reservation> holds = heldReservationsQuietly(tenantId, orderId);
    for (Reservation r : holds) {
      releaseQuietly(tenantId, r.id());
    }
    if (!holds.isEmpty()) {
      LOG.log(Level.INFO, "OrderCancelled {0}: released {1} hold(s)", orderId, holds.size());
    }
  }

  private void releaseQuietly(UUID tenantId, UUID reservationId) {
    try {
      service.release(tenantId, reservationId);
    } catch (RuntimeException e) {
      // Already released/consumed or transient — the TTL sweeper is the backstop.
      LOG.log(Level.WARNING, "release of hold {0} failed: {1}", reservationId, e.getMessage());
    }
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return UUID.nameUUIDFromBytes(
        (CONSUMER_NAME + ":" + eventId + ":" + lineIndex).getBytes(StandardCharsets.UTF_8));
  }
}
