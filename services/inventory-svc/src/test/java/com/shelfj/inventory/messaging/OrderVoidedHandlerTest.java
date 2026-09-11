package com.shelfj.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A voided till sale puts its stock back (SJ-D40).
 *
 * <p>Before SJ-D40 a till sale never deducted stock, so nothing listened for OrderVoided and
 * nothing needed to. Once a paid till sale is fulfilled at the counter, a void that does not
 * restock loses the stock permanently.
 */
class OrderVoidedHandlerTest {

  private static final UUID EVENT = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
  private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final UUID ORDER = UUID.fromString("22222222-0000-0000-0000-000000000001");
  private static final UUID STORE = UUID.fromString("33333333-0000-0000-0000-000000000001");
  private static final UUID APPLES = UUID.fromString("44444444-0000-0000-0000-000000000001");
  private static final UUID CHEESE = UUID.fromString("44444444-0000-0000-0000-000000000002");

  /**
   * Records the two receive paths. Every other service method is left un-overridden, so if the
   * handler ever deducted or consumed a hold for a void it would reach the real, unwired method and
   * fail the test loudly — which is the assertion that a void never deducts.
   */
  static final class RecordingService extends InventoryService {
    final List<String> calls = new ArrayList<>();

    @Override
    public boolean receiveVoidFromOrderOnce(
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal qty,
        UUID orderId) {
      calls.add(line("VOID", dedupeId, consumerName, tenantId, storeId, variantId, qty, orderId));
      return true;
    }

    @Override
    public boolean receiveReturnFromOrderOnce(
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal qty,
        UUID orderId) {
      calls.add(line("RETURN", dedupeId, consumerName, tenantId, storeId, variantId, qty, orderId));
      return true;
    }

    private static String line(
        String kind,
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal qty,
        UUID orderId) {
      return String.join(
          " ",
          kind,
          dedupeId.toString(),
          consumerName,
          tenantId.toString(),
          storeId.toString(),
          variantId.toString(),
          qty.toPlainString(),
          orderId.toString());
    }
  }

  private RecordingService service;
  private OrderEventHandler handler;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    handler = new OrderEventHandler();
    handler.service = service;
  }

  private static String voided(String items) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"OrderVoided\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + STORE
        + "\",\"items\":["
        + items
        + "]}";
  }

  @Test
  @DisplayName("Every line a void carries is received back, including a weighed quantity")
  void restocksEachLine() {
    handler.handle(
        voided(
            "{\"variantId\":\""
                + APPLES
                + "\",\"qty\":2},{\"variantId\":\""
                + CHEESE
                + "\",\"qty\":0.375}"));

    assertEquals(
        List.of(
            RecordingService.line(
                "VOID",
                OrderEventHandler.lineDedupeId(EVENT, 0),
                OrderEventHandler.CONSUMER_NAME,
                TENANT,
                STORE,
                APPLES,
                new BigDecimal("2"),
                ORDER),
            RecordingService.line(
                "VOID",
                OrderEventHandler.lineDedupeId(EVENT, 1),
                OrderEventHandler.CONSUMER_NAME,
                TENANT,
                STORE,
                CHEESE,
                new BigDecimal("0.375"),
                ORDER)),
        service.calls);
  }

  @Test
  @DisplayName("A void is recorded as a void, never as a return")
  void aVoidIsNotAReturn() {
    handler.handle(voided("{\"variantId\":\"" + APPLES + "\",\"qty\":1}"));

    // Voids and returns are different loss-prevention signals; recording one as the other hides a
    // sale voided after the money was taken.
    assertEquals(1, service.calls.size());
    assertTrue(service.calls.get(0).startsWith("VOID "), service.calls.get(0));
  }

  @Test
  @DisplayName("A sale voided before it was handed over puts nothing back")
  void nothingHandedOverNothingBack() {
    // Nothing was deducted, so restocking here would invent stock.
    handler.handle(voided(""));
    assertEquals(List.of(), service.calls);
  }

  @Test
  @DisplayName("An OrderVoided from before SJ-D40 is skipped, not crashed on")
  void legacyShapeIsSkipped() {
    // The old payload carried no eventId, storeId or items. Subscribing to the topic may replay
    // them; the till sales voided then had never deducted stock, so skipping is correct.
    handler.handle(
        "{\"eventType\":\"OrderVoided\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}");
    assertEquals(List.of(), service.calls);
  }
}
