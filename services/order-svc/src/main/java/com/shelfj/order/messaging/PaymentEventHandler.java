package com.shelfj.order.messaging;

import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles PaymentCaptured / PaymentFailed events; delegates to {@link OrderService}. Naturally
 * idempotent: the order transition is guarded on PENDING status, so redelivery is a no-op.
 * Malformed payloads and 4xx business conflicts (already transitioned) are skipped; transient
 * failures propagate so the consumer loop redelivers instead of losing the event.
 */
@ApplicationScoped
class PaymentEventHandler {

  private static final Logger LOG = System.getLogger(PaymentEventHandler.class.getName());
  private static final Pattern EVENT_TYPE_PAT = Pattern.compile("\"eventType\"\\s*:\\s*\"(\\w+)\"");
  private static final Pattern ORDER_ID_PAT =
      Pattern.compile("\"orderId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern TENANT_ID_PAT =
      Pattern.compile("\"tenantId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern AMOUNT_PAT =
      Pattern.compile("\"amount\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

  @Inject OrderService svc;

  void handle(String payload) {
    String eventType;
    UUID orderId;
    UUID tenantId;
    BigDecimal amount;
    try {
      eventType = extract(EVENT_TYPE_PAT, payload);
      String orderIdStr = extract(ORDER_ID_PAT, payload);
      String tenantIdStr = extract(TENANT_ID_PAT, payload);
      String amountStr = extract(AMOUNT_PAT, payload);
      if (orderIdStr == null || tenantIdStr == null) return;
      orderId = UUID.fromString(orderIdStr);
      tenantId = UUID.fromString(tenantIdStr);
      amount = amountStr != null ? new BigDecimal(amountStr) : null;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed payment event skipped: " + e.getMessage());
      return;
    }

    try {
      if ("PaymentCaptured".equals(eventType)) {
        svc.handlePaymentCaptured(tenantId, orderId, amount);
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

  private static String extract(Pattern p, String s) {
    if (s == null) return null;
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}
