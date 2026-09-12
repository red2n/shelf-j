package com.shelfj.order.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shelfj.ids.Ids;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PaymentEventHandler used to parse Kafka payloads with hand-rolled regex, which silently grabbed
 * the wrong value whenever a field name was duplicated anywhere else in the payload (e.g. inside a
 * nested object) and broke on any innocuous schema change. It now parses with jakarta.json instead.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID PAYMENT = Ids.newId();

  @Mock OrderService svc;

  private PaymentEventHandler handler;

  @BeforeEach
  void setUp() {
    handler = new PaymentEventHandler();
    handler.svc = svc;
  }

  @Test
  void paymentCapturedDispatchesWithParsedFields() {
    String payload =
        "{\"eventType\":\"PaymentCaptured\",\"orderId\":\""
            + ORDER
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"paymentId\":\""
            + PAYMENT
            + "\",\"amount\":42.50}";

    handler.handle(payload);

    verify(svc).handlePaymentCaptured(TENANT, ORDER, PAYMENT, new BigDecimal("42.50"));
  }

  @Test
  void paymentFailedDispatchesWithoutRequiringPaymentIdOrAmount() {
    String payload =
        "{\"eventType\":\"PaymentFailed\",\"orderId\":\""
            + ORDER
            + "\",\"tenantId\":\""
            + TENANT
            + "\"}";

    handler.handle(payload);

    verify(svc).handlePaymentFailed(TENANT, ORDER);
  }

  @Test
  void fieldOrderAndExtraWhitespaceDoNotAffectParsing() {
    // A real JSON parser doesn't care about key order or formatting the way the old regex
    // (which scanned for the first raw substring match) did.
    String payload =
        "{\n  \"amount\" : 10,\n  \"tenantId\":\""
            + TENANT
            + "\" ,\"eventType\":\"PaymentCaptured\", \"paymentId\":\""
            + PAYMENT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}";

    handler.handle(payload);

    verify(svc).handlePaymentCaptured(TENANT, ORDER, PAYMENT, BigDecimal.TEN);
  }

  @Test
  void aDuplicateFieldNameInsideANestedObjectDoesNotShadowTheTopLevelValue() {
    // Regression: the old regex matched "\"tenantId\"\\s*:\\s*\"...\"" anywhere in the raw
    // string, so a decoy "tenantId" placed earlier in a nested object (e.g. a future "meta"
    // block) would have been picked up instead of the real top-level field.
    UUID decoy = Ids.newId();
    String payload =
        "{\"meta\":{\"tenantId\":\""
            + decoy
            + "\"},\"eventType\":\"PaymentCaptured\",\"orderId\":\""
            + ORDER
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"paymentId\":\""
            + PAYMENT
            + "\",\"amount\":5}";

    handler.handle(payload);

    verify(svc).handlePaymentCaptured(TENANT, ORDER, PAYMENT, BigDecimal.valueOf(5));
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("not json at all");

    verify(svc, never()).handlePaymentCaptured(any(), any(), any(), any());
    verify(svc, never()).handlePaymentFailed(any(), any());
  }

  @Test
  void missingOrderIdIsSkipped() {
    String payload = "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\"" + TENANT + "\"}";

    handler.handle(payload);

    verify(svc, never()).handlePaymentCaptured(any(), any(), any(), any());
  }

  @Test
  void a5xxFromTheServiceLayerPropagatesForRedelivery() {
    String payload =
        "{\"eventType\":\"PaymentCaptured\",\"orderId\":\""
            + ORDER
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"paymentId\":\""
            + PAYMENT
            + "\",\"amount\":1}";
    doThrow(new ApiException(500, "DB_ERROR", "boom", List.of()))
        .when(svc)
        .handlePaymentCaptured(any(), any(), any(), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        ApiException.class, () -> handler.handle(payload));
  }

  @Test
  void a4xxFromTheServiceLayerIsSwallowedAsABusinessConflict() {
    String payload =
        "{\"eventType\":\"PaymentCaptured\",\"orderId\":\""
            + ORDER
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"paymentId\":\""
            + PAYMENT
            + "\",\"amount\":1}";
    doThrow(ApiException.conflict("ORDER_ALREADY_TRANSITIONED", "already confirmed"))
        .when(svc)
        .handlePaymentCaptured(any(), any(), any(), any());

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.handle(payload));
  }
}
