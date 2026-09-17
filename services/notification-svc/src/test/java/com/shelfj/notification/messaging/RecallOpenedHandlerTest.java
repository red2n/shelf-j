package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.notification.service.NotifierTestSupport;
import com.shelfj.notification.service.OnceRepo;
import com.shelfj.notification.service.RecordingChannel;
import jakarta.json.Json;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An opened recall tells each store whose stock it held, once each, even when the event is
 * redelivered — and a recall, unlike a withdrawal, reminds the store to display the notice.
 */
class RecallOpenedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();

  private RecordingChannel channel;
  private RecallOpenedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    handler = new RecallOpenedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new OnceRepo());
  }

  @Test
  void everyStoreHoldingTheStockIsToldOnceEvenOnRedelivery() {
    String payload = payload("RECALL", "ALLERGEN", STORE_A, STORE_B);

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(List.of(STORE_A.toString(), STORE_B.toString()), channel.recipients);
    assertEquals("Product recall FSA-PRIN-42-2026: stock taken off sale", channel.subject());
    assertEquals(
        "Product recall FSA-PRIN-42-2026 (undeclared allergen) has taken stock at this store off"
            + " sale. Pull it from the shelves and record what you found on the Recalls screen,"
            + " and display the recall notice at the tills.",
        channel.body());
  }

  @Test
  void aWithdrawalAsksForNoNoticeAndAnUnknownHazardStillReads() {
    handler.handle(payload("WITHDRAWAL", "SOMETHING_NEW", STORE_A));

    assertEquals(1, channel.recipients.size());
    assertTrue(channel.body().startsWith("Product withdrawal FSA-PRIN-42-2026 (safety issue)"));
    assertTrue(channel.body().endsWith("on the Recalls screen."));
  }

  @Test
  void aRecallThatHeldNoStockTellsNobodyAndAMalformedOneIsSkipped() {
    handler.handle(payload("RECALL", "CHEMICAL"));
    handler.handle("{\"eventId\":\"nope\"}");
    assertEquals(0, channel.recipients.size());
  }

  private static String payload(String kind, String hazard, UUID... stores) {
    var ids = Json.createArrayBuilder();
    for (UUID s : stores) {
      ids.add(s.toString());
    }
    return Json.createObjectBuilder()
        .add("eventId", Ids.newId().toString())
        .add("tenantId", TENANT.toString())
        .add("reference", "FSA-PRIN-42-2026")
        .add("kind", kind)
        .add("hazard", hazard)
        .add("storeIds", ids)
        .build()
        .toString();
  }
}
