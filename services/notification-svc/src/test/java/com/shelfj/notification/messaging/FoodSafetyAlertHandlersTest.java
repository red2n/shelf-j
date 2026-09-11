package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.ids.Ids;
import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.notification.service.NotifierTestSupport;
import jakarta.json.Json;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A failed or missed food-safety check reaches the store's devices once per event, says what failed
 * in words staff can act on, and a malformed payload pushes nothing.
 */
class FoodSafetyAlertHandlersTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  private static final class FakeChannel implements NotificationChannel {
    int sends;
    String recipient;
    String subject;
    String body;

    @Override
    public String name() {
      return "FAKE";
    }

    @Override
    public void send(UUID tenantId, String recipient, String subject, String body) {
      sends++;
      this.recipient = recipient;
      this.subject = subject;
      this.body = body;
    }
  }

  private static final class FakeRepo extends NotificationRepository {
    boolean notified;
    UUID subjectId = Ids.newId();

    @Override
    public boolean alreadyNotified(UUID eventId, String type) {
      return notified;
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
      this.subjectId = subjectId;
      notified = true;
    }
  }

  private FakeChannel channel;
  private FakeRepo repo;
  private FoodSafetyCheckFailedHandler failed;
  private FoodSafetyCheckOverdueHandler overdue;

  @BeforeEach
  void setUp() {
    channel = new FakeChannel();
    repo = new FakeRepo();
    failed = new FoodSafetyCheckFailedHandler();
    failed.notifier = NotifierTestSupport.notifierOf(channel, repo);
    overdue = new FoodSafetyCheckOverdueHandler();
    overdue.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  @Test
  void aWarmChillerTellsTheStoreTheReadingAndTheLimitOnce() {
    String payload =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("tenantId", TENANT.toString())
            .add("storeId", STORE.toString())
            .add("pointName", "Dairy chiller 1")
            .add("checkTypeCode", "CHILLED_STORAGE")
            .add("kind", "TEMPERATURE")
            .add("value", new BigDecimal("9.50"))
            .add("unit", "C")
            .addNull("minValue")
            .add("maxValue", new BigDecimal("8.00"))
            .build()
            .toString();

    failed.handle(payload);
    failed.handle(payload);

    assertEquals(1, channel.sends, "a redelivered event must not alert the store twice");
    assertEquals(STORE.toString(), channel.recipient);
    assertEquals("Food safety check failed: Dairy chiller 1", channel.subject);
    assertEquals(
        "Dairy chiller 1 read 9.50 °C against a limit of at most 8.00 °C. Record what was done"
            + " about it on the Food safety screen.",
        channel.body);
    // A store's devices, not a person: nothing here for an erasure to find.
    assertNull(repo.subjectId);
  }

  @Test
  void aFailedChecklistAndBothKindsOfLimitReadNaturally() {
    assertEquals(
        "Opening checks was recorded as failed. Record what was done about it on the Food safety"
            + " screen.",
        FoodSafetyCheckFailedHandler.describe("Opening checks", null, null, null));
    assertEquals(
        "Hot cabinet read 58.00 °C against a limit of at least 63.00 °C. Record what was done about"
            + " it on the Food safety screen.",
        FoodSafetyCheckFailedHandler.describe(
            "Hot cabinet", new BigDecimal("58.00"), new BigDecimal("63.00"), null));
    assertEquals(
        "Chiller read -1.00 °C against limits of 0.00 °C to 5.00 °C. Record what was done about it"
            + " on the Food safety screen.",
        FoodSafetyCheckFailedHandler.describe(
            "Chiller", new BigDecimal("-1.00"), new BigDecimal("0.00"), new BigDecimal("5.00")));
  }

  @Test
  void aMissedCheckSaysWhenItWasDueInUtc() {
    overdue.handle(
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("tenantId", TENANT.toString())
            .add("storeId", STORE.toString())
            .add("pointName", "Butchery chiller")
            .add("checkTypeCode", "CHILLED_STORAGE")
            .add("dueSince", "2026-09-11T12:00:00Z")
            .build()
            .toString());

    assertEquals(1, channel.sends);
    assertEquals("Food safety check overdue: Butchery chiller", channel.subject);
    assertEquals(
        "Butchery chiller was due a check at 2026-09-11 12:00 UTC and none has been recorded. Take"
            + " it now on the Food safety screen.",
        channel.body);
  }

  @Test
  void aMalformedPayloadAlertsNobody() {
    failed.handle("{\"eventId\":\"not-a-uuid\"}");
    overdue.handle("not json at all");
    assertEquals(0, channel.sends);
  }
}
