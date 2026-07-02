package com.shelfj.order.messaging;

import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
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
 * Handles PaymentCaptured / PaymentFailed events; delegates to {@link OrderService}.
 * PaymentCaptured is idempotent via the {@code order_payment_events} ledger keyed on {@code
 * paymentId} (golden rule #7), so redelivery of the same tender is a no-op. Malformed payloads and
 * 4xx business conflicts are skipped; transient failures propagate so the consumer loop redelivers
 * instead of losing the event.
 */
@ApplicationScoped
class PaymentEventHandler {

  private static final Logger LOG = System.getLogger(PaymentEventHandler.class.getName());

  @Inject OrderService svc;

  void handle(String payload) {
    String eventType;
    UUID orderId;
    UUID tenantId;
    UUID paymentId;
    BigDecimal amount;
    try {
      JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
      eventType = stringOrNull(obj, "eventType");
      String orderIdStr = stringOrNull(obj, "orderId");
      String tenantIdStr = stringOrNull(obj, "tenantId");
      String paymentIdStr = stringOrNull(obj, "paymentId");
      if (orderIdStr == null || tenantIdStr == null) return;
      orderId = UUID.fromString(orderIdStr);
      tenantId = UUID.fromString(tenantIdStr);
      paymentId = paymentIdStr != null ? UUID.fromString(paymentIdStr) : null;
      amount =
          obj.containsKey("amount") && !obj.isNull("amount")
              ? obj.getJsonNumber("amount").bigDecimalValue()
              : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed payment event skipped: " + e.getMessage());
      return;
    }

    try {
      if ("PaymentCaptured".equals(eventType)) {
        svc.handlePaymentCaptured(tenantId, orderId, paymentId, amount);
      } else if ("PaymentFailed".equals(eventType)) {
        svc.handlePaymentFailed(tenantId, orderId);
      }
    } catch (ApiException e) {
      if (e.status() >= 500) {
        throw e; // transient (DB etc.) — let the consumer loop redeliver
      }
      // 4xx = business conflict (e.g. order already transitioned) — redelivery cannot fix it
      LOG.log(Level.WARNING, "Payment event for order {0} skipped: {1}", orderId, e.getMessage());
    }
  }

  private static String stringOrNull(JsonObject o, String key) {
    return o.containsKey(key) && !o.isNull(key) ? o.getString(key) : null;
  }
}
