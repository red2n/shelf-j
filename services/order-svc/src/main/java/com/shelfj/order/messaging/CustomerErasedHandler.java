package com.shelfj.order.messaging;

import com.shelfj.order.service.OrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Handles CustomerErased from customer-svc (SJ-D43): a shop erased a customer, so its orders must
 * stop identifying them. Idempotent on the event id (golden rule 7). A malformed payload is
 * skipped; a transient failure propagates so the consumer loop redelivers rather than losing the
 * erasure.
 */
@ApplicationScoped
class CustomerErasedHandler {

  private static final Logger LOG = System.getLogger(CustomerErasedHandler.class.getName());

  @Inject OrderService svc;

  void handle(String payload) {
    UUID eventId;
    UUID tenantId;
    UUID customerId;
    try (var reader = Json.createReader(new StringReader(payload))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      customerId = UUID.fromString(obj.getString("customerId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed CustomerErased payload skipped: " + e.getMessage());
      return;
    }
    if (svc.handleCustomerErased(tenantId, customerId, eventId)) {
      LOG.log(
          Level.INFO,
          "Customer {0} erased: settled orders redacted, open orders held until they finish",
          customerId);
    }
  }
}
