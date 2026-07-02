package com.shelfj.order.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shelfj.order.repo.OrderRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * InventoryEventHandler used to parse Kafka payloads with hand-rolled regex; it now parses with
 * jakarta.json instead (see PaymentEventHandlerTest for the same regression rationale).
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventHandlerTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID STORE = UUID.randomUUID();
  private static final UUID VARIANT = UUID.randomUUID();

  @Mock OrderRepository repo;

  private InventoryEventHandler handler;

  @BeforeEach
  void setUp() {
    handler = new InventoryEventHandler();
    handler.repo = repo;
    org.mockito.Mockito.lenient()
        .when(repo.upsertStockPositionOnce(any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
  }

  private static String payload(String eventType, String qtyOrDeltaField, String value) {
    return "{\"eventType\":\""
        + eventType
        + "\",\"eventId\":\""
        + EVENT
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"storeId\":\""
        + STORE
        + "\",\"variantId\":\""
        + VARIANT
        + "\",\""
        + qtyOrDeltaField
        + "\":"
        + value
        + "}";
  }

  @Test
  void stockReceivedAppliesAPositiveDelta() {
    handler.handle(payload("StockReceived", "qty", "5"));

    verify(repo)
        .upsertStockPositionOnce(
            eq(EVENT),
            eq(InventoryEventHandler.CONSUMER_NAME),
            eq(TENANT),
            eq(STORE),
            eq(VARIANT),
            eq(new BigDecimal("5")));
  }

  @Test
  void stockDeductedNegatesTheQty() {
    handler.handle(payload("StockDeducted", "qty", "3"));

    verify(repo)
        .upsertStockPositionOnce(
            eq(EVENT), any(), eq(TENANT), eq(STORE), eq(VARIANT), eq(new BigDecimal("-3")));
  }

  @Test
  void stockAdjustedUsesTheSignedDeltaAsIs() {
    handler.handle(payload("StockAdjusted", "delta", "-2.5"));

    verify(repo)
        .upsertStockPositionOnce(
            eq(EVENT), any(), eq(TENANT), eq(STORE), eq(VARIANT), eq(new BigDecimal("-2.5")));
  }

  @Test
  void unknownEventTypeIsIgnored() {
    handler.handle(payload("SomeFutureEvent", "qty", "1"));

    verify(repo, never()).upsertStockPositionOnce(any(), any(), any(), any(), any(), any());
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("{not valid json");

    verify(repo, never()).upsertStockPositionOnce(any(), any(), any(), any(), any(), any());
  }

  @Test
  void missingQtyIsSkipped() {
    String payload =
        "{\"eventType\":\"StockReceived\",\"eventId\":\""
            + EVENT
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"storeId\":\""
            + STORE
            + "\",\"variantId\":\""
            + VARIANT
            + "\"}";

    handler.handle(payload);

    verify(repo, never()).upsertStockPositionOnce(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aDuplicateFieldNameInsideANestedObjectDoesNotShadowTheTopLevelValue() {
    // Regression: the old regex matched anywhere in the raw string, so a decoy "storeId" earlier
    // in the payload (e.g. inside a "previous" block) would have been picked up instead of the
    // real top-level field.
    UUID decoyStore = UUID.randomUUID();
    String payload =
        "{\"previous\":{\"storeId\":\""
            + decoyStore
            + "\"},\"eventType\":\"StockReceived\",\"eventId\":\""
            + EVENT
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"storeId\":\""
            + STORE
            + "\",\"variantId\":\""
            + VARIANT
            + "\",\"qty\":7}";

    handler.handle(payload);

    verify(repo)
        .upsertStockPositionOnce(
            eq(EVENT), any(), eq(TENANT), eq(STORE), eq(VARIANT), eq(new BigDecimal("7")));
  }
}
