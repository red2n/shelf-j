package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.notification.service.NotifierTestSupport;
import jakarta.json.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  private static final class FakeChannel implements NotificationChannel {
    final List<String> recipients = new ArrayList<>();
    String subject;
    String body;

    @Override
    public String name() {
      return "FAKE";
    }

    @Override
    public void send(UUID tenantId, String recipient, String subject, String body) {
      recipients.add(recipient);
      this.subject = subject;
      this.body = body;
    }
  }

  private static final class FakeRepo extends NotificationRepository {
    final Set<UUID> notified = new HashSet<>();

    @Override
    public boolean alreadyNotified(UUID eventId, String type) {
      return notified.contains(eventId);
    }

    @Override
    public void recordNotification(
        UUID tenantId,
        UUID subjectId,
        UUID eventId,
        String type,
        String channel,
        String recipient,
        String subject,
        String body,
        String status) {
      notified.add(eventId);
    }
  }

  private FakeChannel channel;
  private RecallOpenedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new FakeChannel();
    handler = new RecallOpenedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, new FakeRepo());
  }

  @Test
  void everyStoreHoldingTheStockIsToldOnceEvenOnRedelivery() {
    String payload = payload("RECALL", "ALLERGEN", STORE_A, STORE_B);

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(List.of(STORE_A.toString(), STORE_B.toString()), channel.recipients);
    assertEquals("Product recall FSA-PRIN-42-2026: stock taken off sale", channel.subject);
    assertEquals(
        "Product recall FSA-PRIN-42-2026 (undeclared allergen) has taken stock at this store off"
            + " sale. Pull it from the shelves and record what you found on the Recalls screen,"
            + " and display the recall notice at the tills.",
        channel.body);
  }

  @Test
  void aWithdrawalAsksForNoNoticeAndAnUnknownHazardStillReads() {
    handler.handle(payload("WITHDRAWAL", "SOMETHING_NEW", STORE_A));

    assertEquals(1, channel.recipients.size());
    assertTrue(channel.body.startsWith("Product withdrawal FSA-PRIN-42-2026 (safety issue)"));
    assertTrue(channel.body.endsWith("on the Recalls screen."));
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
