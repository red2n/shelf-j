package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.ids.Ids;
import com.shelfj.notification.service.NotifierTestSupport;
import com.shelfj.notification.service.OnceRepo;
import com.shelfj.notification.service.RecordingChannel;
import jakarta.json.Json;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A missed task reaches the store's devices once per event, says what was missed in words staff can
 * act on, and a malformed payload pushes nothing.
 */
class StoreTaskMissedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  private RecordingChannel channel;
  private OnceRepo repo;
  private StoreTaskMissedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    handler = new StoreTaskMissedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  @Test
  void aMissedClosingListTellsTheStoreOnce() {
    String payload =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("tenantId", TENANT.toString())
            .add("storeId", STORE.toString())
            .add("title", "Lock up")
            .add("kind", "CLOSING")
            .add("businessDate", "2026-09-19")
            .add("dueAt", "2026-09-19T21:00:00Z")
            .add("required", true)
            .build()
            .toString();

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(1, channel.sends(), "a redelivered miss must not nag the store twice");
    assertEquals(STORE.toString(), channel.recipient());
    assertEquals("Not done: Lock up", channel.subject());
    assertEquals(
        "The closing list \"Lock up\" for 2026-09-19 fell due and was not done, and it is required."
            + " Do it now if it still can be, or record why it was skipped.",
        channel.body());
    // A store's devices, not a person: nothing here for an erasure to find.
    assertNull(repo.subjectId);
  }

  @Test
  void anOptionalTaskAndAnUnknownKindReadNaturally() {
    assertEquals(
        "The task \"Water the plants\" for 2026-09-19 fell due and was not done. Do it now if it"
            + " still can be, or record why it was skipped.",
        StoreTaskMissedHandler.describe("Water the plants", "WEEKLY", "2026-09-19", false));
    assertEquals(
        "The opening list \"Open up\" for 2026-09-19 fell due and was not done, and it is required."
            + " Do it now if it still can be, or record why it was skipped.",
        StoreTaskMissedHandler.describe("Open up", "opening", "2026-09-19", true));
  }

  @Test
  void aMalformedPayloadPushesNothing() {
    handler.handle("{\"eventId\":\"not-an-id\"}");
    handler.handle("not json at all");
    assertEquals(0, channel.sends());
  }
}
