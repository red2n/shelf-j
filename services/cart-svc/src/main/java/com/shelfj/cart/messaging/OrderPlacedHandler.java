package com.shelfj.cart.messaging;

import com.shelfj.cart.service.CartService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.order.order-placed}. When an order is placed for a known
 * customer, marks that customer's active cart at that store as CHECKED_OUT so it no longer appears
 * as their open cart. Guest/POS orders (no customerId in the event) are silently skipped.
 *
 * <p>Idempotent: UPDATE WHERE status='ACTIVE' is a no-op if the cart is already CHECKED_OUT.
 */
@ApplicationScoped
class OrderPlacedHandler {

  private static final Logger LOG = System.getLogger(OrderPlacedHandler.class.getName());

  @Inject CartService cartService;

  void handle(String json) {
    UUID tenantId;
    UUID customerId;
    UUID storeId;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      tenantId = UUID.fromString(obj.getString("tenantId"));
      // A cart is held under the login the shopper signed in with, which is not the shop's
      // customer id (SJ-D44) — before that was unpicked, one value stood in both places. loginId
      // is what matches a cart; customerId is the fallback for events published before the split.
      String basketOwner =
          obj.containsKey("loginId") && !obj.isNull("loginId")
              ? obj.getString("loginId")
              : (obj.isNull("customerId") ? null : obj.getString("customerId"));
      customerId = basketOwner == null ? null : UUID.fromString(basketOwner);
      String storeIdStr = obj.getString("storeId", null);
      storeId = storeIdStr != null ? UUID.fromString(storeIdStr) : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed OrderPlaced payload skipped: " + e.getMessage());
      return;
    }

    if (customerId == null || storeId == null) {
      return; // POS or anonymous order — no cart to close
    }

    cartService.onOrderPlaced(tenantId, customerId, storeId);
    LOG.log(
        Level.INFO, "Cart marked CHECKED_OUT for customer {0} at store {1}", customerId, storeId);
  }
}
