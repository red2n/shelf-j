package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A landed charge reaches every line of the receipt once, with its sign; a reversal is the same
 * lines negated; a receipt not yet booked is redelivered, not skipped; a malformed payload moves
 * nothing.
 */
class LandedCostHandlerTest {

  private static final UUID EVENT = UUID.fromString("01a0be6a-611e-705b-8bb0-8fccd45e4301");
  private static final UUID TENANT = UUID.fromString("01a0be6a-611e-700a-9f77-b94950c4c25a");
  private static final UUID STORE = UUID.fromString("01a0be6a-611e-7010-be82-c788cf35ea1c");
  private static final UUID RECEIPT = UUID.fromString("01a0be6a-611e-7011-be82-c788cf35ea1c");
  private static final UUID CHARGE = UUID.fromString("01a0be6a-611e-7016-be82-c788cf35ea1c");
  private static final UUID APPLES = UUID.fromString("01a0be6a-611e-7012-a14d-4f924d594f00");
  private static final UUID PEARS = UUID.fromString("01a0be6a-611e-7013-9a2a-bd6e8545fd8a");

  static final class RecordingService extends InventoryService {
    final List<String> calls = new ArrayList<>();
    boolean receiptNotBooked;
    UUID refuse;

    @Override
    public boolean revalueReceiptOnce(
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        UUID grId,
        BigDecimal perUnit,
        BigDecimal amount,
        String sourceType,
        UUID sourceId) {
      if (receiptNotBooked) {
        throw new ApiException(503, "INVENTORY_RECEIPT_NOT_YET_BOOKED", "not yet", List.of());
      }
      if (variantId.equals(refuse)) {
        throw ApiException.notFound("BATCH_NOT_FOUND", "gone");
      }
      calls.add(
          String.join(
              " ",
              dedupeId.toString(),
              consumerName,
              tenantId.toString(),
              storeId.toString(),
              variantId.toString(),
              grId.toString(),
              perUnit.toPlainString(),
              amount.toPlainString(),
              sourceType,
              sourceId.toString()));
      return true;
    }
  }

  private RecordingService service;
  private LandedCostHandler handler;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    handler = new LandedCostHandler();
    handler.service = service;
  }

  private static String event(String type, String lines) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"storeId\":\""
        + STORE
        + "\",\"refId\":\""
        + RECEIPT
        + "\",\"landedCostId\":\""
        + CHARGE
        + "\",\"poId\":\"01a0be6a-611e-7014-9a2a-bd6e8545fd00\",\"chargeType\":\"FREIGHT\","
        + "\"currency\":\"GBP\",\"amount\":10.00,\"lines\":["
        + lines
        + "]}";
  }

  private static String line(UUID variant, String qty, String amount, String perUnit) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"amount\":"
        + amount
        + ",\"perUnit\":"
        + perUnit
        + "}";
  }

  @Test
  void aChargeLiftsEveryLineOfTheReceiptWithItsOwnDedupeId() {
    handler.handle(
        event(
            "LandedCostApplied",
            line(APPLES, "10", "6.25", "0.6250") + "," + line(PEARS, "5", "3.75", "0.7500")));

    assertEquals(2, service.calls.size());
    String apples = service.calls.get(0);
    assertTrue(
        apples.contains(APPLES + " " + RECEIPT + " 0.6250 6.25 LANDED_COST " + CHARGE), apples);
    assertTrue(
        apples.startsWith(LandedCostHandler.lineDedupeId(EVENT, 0) + " inventory-svc/landed-cost"),
        apples);
    assertTrue(
        service.calls.get(1).startsWith(LandedCostHandler.lineDedupeId(EVENT, 1).toString()));
  }

  @Test
  void aReversalIsTheSameLinesNegated() {
    handler.handle(event("LandedCostReversed", line(APPLES, "10", "6.25", "0.6250")));

    assertEquals(1, service.calls.size());
    assertTrue(
        service.calls.get(0).contains(" -0.6250 -6.25 LANDED_COST_REVERSAL "),
        service.calls.get(0));
  }

  @Test
  void aReceiptNotYetBookedIsRedeliveredNotSkipped() {
    service.receiptNotBooked = true;
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> handler.handle(event("LandedCostApplied", line(APPLES, "10", "6.25", "0.6250"))));
    assertEquals(503, e.status());
    assertEquals(0, service.calls.size());
  }

  @Test
  void aLineThatCannotBeAppliedIsSkippedAndTheOthersStillLand() {
    service.refuse = APPLES;
    handler.handle(
        event(
            "LandedCostApplied",
            line(APPLES, "10", "6.25", "0.6250") + "," + line(PEARS, "5", "3.75", "0.7500")));
    assertEquals(1, service.calls.size());
    assertTrue(service.calls.get(0).contains(PEARS.toString()));
  }

  @Test
  void aMalformedPayloadMovesNothing() {
    handler.handle("{\"eventId\":\"not-a-uuid\"}");
    handler.handle("not json");
    handler.handle(event("LandedCostApplied", "").replace("\"lines\":[]", "\"lines\":null"));
    assertEquals(0, service.calls.size());
  }
}
