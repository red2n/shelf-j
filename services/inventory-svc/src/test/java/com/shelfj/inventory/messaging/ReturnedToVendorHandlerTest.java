package com.shelfj.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Goods going back to the supplier leave the store's stock (07.8): once per line, deduped on the
 * event, a line the store cannot cover skipped with a warning rather than poisoning the topic, a
 * transient failure redelivered.
 */
class ReturnedToVendorHandlerTest {

  private static final UUID EVENT = UUID.fromString("01a090ae-611e-705b-8bb0-8fccd45e4301");
  private static final UUID TENANT = UUID.fromString("01a090ae-611e-700a-9f77-b94950c4c25a");
  private static final UUID STORE = UUID.fromString("01a090ae-611e-7010-be82-c788cf35ea1c");
  private static final UUID APPLES = UUID.fromString("01a090ae-611e-7012-a14d-4f924d594f00");
  private static final UUID CHEESE = UUID.fromString("01a090ae-611e-7013-9a2a-bd6e8545fd8a");

  static final class RecordingService extends InventoryService {
    final List<String> calls = new ArrayList<>();
    UUID shortOf;
    boolean transientFailure;

    @Override
    public boolean returnToVendorOnce(
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal qty,
        UUID returnId) {
      if (transientFailure) {
        throw new ApiException(503, "DB_UNAVAILABLE", "down", List.of());
      }
      if (variantId.equals(shortOf)) {
        throw ApiException.unprocessable("INSUFFICIENT_STOCK", "Short by 1 during deduction");
      }
      calls.add(
          String.join(
              " ",
              dedupeId.toString(),
              consumerName,
              tenantId.toString(),
              storeId.toString(),
              variantId.toString(),
              qty.toPlainString(),
              returnId.toString()));
      return true;
    }
  }

  private RecordingService service;
  private ReturnedToVendorHandler handler;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    handler = new ReturnedToVendorHandler();
    handler.service = service;
  }

  private static String event(String lines) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"ReturnedToVendor\",\"tenantId\":\""
        + TENANT
        + "\",\"storeId\":\""
        + STORE
        + "\",\"refId\":\""
        + EVENT
        + "\",\"poId\":\"01a090ae-611e-7014-9a2a-bd6e8545fd00\",\"supplierId\":\"01a090ae-611e-7015-9a2a-bd6e8545fd00\",\"lines\":["
        + lines
        + "]}";
  }

  @Test
  void everyLineLeavesStockAgainstTheReturnWithItsOwnDedupeId() {
    handler.handle(
        event(
            "{\"variantId\":\""
                + APPLES
                + "\",\"qty\":3},{\"variantId\":\""
                + CHEESE
                + "\",\"qty\":1.5}"));
    assertEquals(2, service.calls.size());
    String first = service.calls.get(0);
    assertTrue(first.contains(ReturnedToVendorHandler.CONSUMER_NAME), first);
    assertTrue(first.contains(APPLES + " 3 " + EVENT), first);
    assertTrue(service.calls.get(1).contains(CHEESE + " 1.5 " + EVENT), service.calls.get(1));
    // Deterministic per line: a redelivery derives the same ids.
    assertEquals(
        ReturnedToVendorHandler.lineDedupeId(EVENT, 0),
        ReturnedToVendorHandler.lineDedupeId(EVENT, 0));
    assertTrue(
        !ReturnedToVendorHandler.lineDedupeId(EVENT, 0)
            .equals(ReturnedToVendorHandler.lineDedupeId(EVENT, 1)));
  }

  @Test
  void aLineTheStoreCannotCoverIsSkippedAndTheRestStillGo() {
    service.shortOf = APPLES;
    handler.handle(
        event(
            "{\"variantId\":\""
                + APPLES
                + "\",\"qty\":3},{\"variantId\":\""
                + CHEESE
                + "\",\"qty\":1}"));
    assertEquals(1, service.calls.size());
    assertTrue(service.calls.get(0).contains(CHEESE.toString()));
  }

  @Test
  void aTransientFailurePropagatesSoTheEventIsRedelivered() {
    service.transientFailure = true;
    assertThrows(
        ApiException.class,
        () -> handler.handle(event("{\"variantId\":\"" + APPLES + "\",\"qty\":3}")));
  }

  @Test
  void aMalformedPayloadIsSkippedWithoutTouchingStock() {
    handler.handle("{\"eventId\":\"not-a-uuid\"}");
    handler.handle(
        "{\"eventId\":\""
            + EVENT
            + "\",\"tenantId\":\""
            + TENANT
            + "\",\"storeId\":\""
            + STORE
            + "\"}");
    assertTrue(service.calls.isEmpty());
  }
}
