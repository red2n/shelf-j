package com.shelfj.order.messaging;

import com.shelfj.order.service.OrderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles PaymentCaptured / PaymentFailed events; delegates to {@link OrderService}. */
@ApplicationScoped
class PaymentEventHandler {

  private static final Logger LOG = System.getLogger(PaymentEventHandler.class.getName());
  private static final Pattern EVENT_TYPE_PAT = Pattern.compile("\"eventType\"\\s*:\\s*\"(\\w+)\"");
  private static final Pattern ORDER_ID_PAT =
      Pattern.compile("\"orderId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern TENANT_ID_PAT =
      Pattern.compile("\"tenantId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

  @Inject OrderService svc;

  void handle(String payload) {
    try {
      String eventType = extract(EVENT_TYPE_PAT, payload);
      String orderIdStr = extract(ORDER_ID_PAT, payload);
      String tenantIdStr = extract(TENANT_ID_PAT, payload);
      if (orderIdStr == null || tenantIdStr == null) return;
      UUID orderId = UUID.fromString(orderIdStr);
      UUID tenantId = UUID.fromString(tenantIdStr);

      if ("PaymentCaptured".equals(eventType)) {
        svc.handlePaymentCaptured(tenantId, orderId);
      } else if ("PaymentFailed".equals(eventType)) {
        svc.handlePaymentFailed(tenantId, orderId);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "PaymentEvent handle error: " + e.getMessage());
    }
  }

  private static String extract(Pattern p, String s) {
    if (s == null) return null;
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}
