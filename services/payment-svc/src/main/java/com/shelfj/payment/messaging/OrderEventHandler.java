package com.shelfj.payment.messaging;

import com.shelfj.payment.service.PaymentService;
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
 * Automatic refunds in response to order events. {@code OrderReturned} refunds the return amount
 * (only when the return went back to the ORIGINAL tender — STORE_CREDIT/GIFT_CARD refunds are
 * settled elsewhere); {@code OrderCancelled} refunds whatever is still captured (unpaid pay-later
 * cancellations are a no-op). Both are idempotent on the order event's {@code eventId} inside
 * {@link PaymentService#refundForOrderEvent}. Separated from {@link OrderEventConsumer} so Kafka
 * lifecycle and domain logic each change for one reason (SRP).
 *
 * <p>Malformed payloads are logged and skipped (they will never parse on redelivery); a failed
 * refund write propagates so the consumer loop redelivers, and the eventId dedupe keeps that safe.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "payment-svc/order-refund";
  static final String REFUND_METHOD_ORIGINAL = "ORIGINAL";

  @Inject PaymentService service;
  @Inject com.shelfj.payment.repo.CashMovementRepository cashMovements;

  void handle(String json) {
    if (json.contains("\"ContainerDepositRefunded\"")) {
      handleContainerRefund(json);
      return;
    }
    String eventType;
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    BigDecimal requestedAmount; // null => cancellation: refund all remaining captured
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventType = obj.getString("eventType", null);
      if ("OrderReturned".equals(eventType)) {
        // Only ORIGINAL-tender returns reverse a payment.
        if (!REFUND_METHOD_ORIGINAL.equals(obj.getString("refundMethod", REFUND_METHOD_ORIGINAL))) {
          return;
        }
        requestedAmount = obj.getJsonNumber("refundAmount").bigDecimalValue();
      } else if ("OrderCancelled".equals(eventType)) {
        requestedAmount = null;
      } else {
        return; // not a refund-triggering event
      }
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      orderId = UUID.fromString(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }

    String reason = requestedAmount == null ? "Order cancelled" : "Return refund";
    service.refundForOrderEvent(eventId, CONSUMER_NAME, tenantId, orderId, requestedAmount, reason);
  }

  /**
   * A deposit refunded at the till for containers brought back (09.16): the cash left the drawer,
   * so the till session carries a pay-out for it, once per event.
   */
  void handleContainerRefund(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID tillSessionId;
    UUID refundedBy;
    BigDecimal amount;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"ContainerDepositRefunded".equals(obj.getString("eventType", null))) return;
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      tillSessionId = UUID.fromString(obj.getString("tillSessionId"));
      refundedBy = UUID.fromString(obj.getString("refundedBy"));
      amount = obj.getJsonNumber("amount").bigDecimalValue();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed container refund event skipped: " + e.getMessage());
      return;
    }
    if (amount.signum() <= 0) return;
    cashMovements.insertMovement(
        tenantId,
        storeId,
        tillSessionId,
        "PAY_OUT",
        amount,
        "Container deposit refund",
        null,
        refundedBy,
        "deposit-refund:" + eventId);
  }
}
