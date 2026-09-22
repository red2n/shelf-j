package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.notification.service.NotifierTestSupport;
import com.storeql.notification.service.OnceRepo;
import com.storeql.notification.service.RecordingChannel;
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

  private RecordingChannel channel;
  private OnceRepo repo;
  private FoodSafetyCheckFailedHandler failed;
  private FoodSafetyCheckOverdueHandler overdue;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
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

    assertEquals(1, channel.sends(), "a redelivered event must not alert the store twice");
    assertEquals(STORE.toString(), channel.recipient());
    assertEquals("Food safety check failed: Dairy chiller 1", channel.subject());
    assertEquals(
        "Dairy chiller 1 read 9.5 °C against a limit of at most 8 °C. Record what was done"
            + " about it on the Food safety screen.",
        channel.body());
    assertEquals("default", repo.template, "the platform's words: the business has written none");
    assertEquals("en", repo.language);
    assertEquals(
        "Dairy chiller 1 read 9.5 °C against a limit of at most 8 °C. Record what was done"
            + " about it on the Food safety screen.",
        channel.body());
    // A store's devices, not a person: nothing here for an erasure to find.
    assertNull(repo.subjectId);
  }

  private void fail(String point, BigDecimal value, BigDecimal min, BigDecimal max) {
    var b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("tenantId", TENANT.toString())
            .add("storeId", STORE.toString())
            .add("pointName", point);
    b = value == null ? b.addNull("value") : b.add("value", value);
    b = min == null ? b.addNull("minValue") : b.add("minValue", min);
    b = max == null ? b.addNull("maxValue") : b.add("maxValue", max);
    failed.handle(b.build().toString());
  }

  @Test
  void aFailedChecklistAndBothKindsOfLimitReadNaturally() {
    fail("Opening checks", null, null, null);
    fail("Hot cabinet", new BigDecimal("58.00"), new BigDecimal("63.00"), null);
    fail("Chiller", new BigDecimal("-1.00"), new BigDecimal("0.00"), new BigDecimal("5.00"));
    assertEquals(
        java.util.List.of(
            "Opening checks was recorded as failed. Record what was done about it on the Food"
                + " safety screen.",
            "Hot cabinet read 58 °C against a limit of at least 63 °C. Record what was done about"
                + " it on the Food safety screen.",
            "Chiller read -1 °C against limits of 0 °C to 5 °C. Record what was done about it on"
                + " the Food safety screen."),
        channel.bodies);
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

    assertEquals(1, channel.sends());
    assertEquals("Food safety check overdue: Butchery chiller", channel.subject());
    assertEquals(
        "Butchery chiller was due a check at 11 September 2026, 12:00 UTC and none has been"
            + " recorded. Take it now on the Food safety screen.",
        channel.body());
  }

  @Test
  void aMalformedPayloadAlertsNobody() {
    failed.handle("{\"eventId\":\"not-a-uuid\"}");
    overdue.handle("not json at all");
    assertEquals(0, channel.sends());
  }
}
