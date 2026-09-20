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
 * An urgent notice wakes the store's devices once; a routine one waits to be read; junk pushes
 * nothing.
 */
class StoreBroadcastPublishedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  private RecordingChannel channel;
  private OnceRepo repo;
  private StoreBroadcastPublishedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    handler = new StoreBroadcastPublishedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  private static String payload(String priority, boolean wake, boolean requiresAck) {
    return Json.createObjectBuilder()
        .add("eventId", Ids.newId().toString())
        .add("tenantId", TENANT.toString())
        .add("storeId", STORE.toString())
        .add("title", "Recall: batch 42 off the shelf")
        .add("priority", priority)
        .add("requiresAck", requiresAck)
        .add("wake", wake)
        .build()
        .toString();
  }

  @Test
  void anUrgentNoticeWakesTheStoreOnce() {
    String p = payload("URGENT", true, true);
    handler.handle(p);
    handler.handle(p);
    assertEquals(1, channel.sends(), "a redelivered notice must not buzz twice");
    assertEquals(STORE.toString(), channel.recipient());
    assertEquals("Urgent notice: Recall: batch 42 off the shelf", channel.subject());
    assertEquals(
        "Management has published \"Recall: batch 42 off the shelf\". Read it on the notices screen and acknowledge it.",
        channel.body());
    assertNull(repo.subjectId, "a store's devices, not a person");
  }

  @Test
  void aRoutineNoticeWaitsToBeRead() {
    handler.handle(payload("INFO", false, false));
    handler.handle(payload("IMPORTANT", false, true));
    assertEquals(
        0,
        channel.sends(),
        "a device that buzzes for every price change gets muted before the recall");
    assertEquals(
        "Management has published \"Bananas\". Read it on the notices screen.",
        StoreBroadcastPublishedHandler.describe("Bananas", false));
  }

  @Test
  void aMalformedPayloadPushesNothing() {
    handler.handle("{\"eventId\":\"nope\"}");
    handler.handle("");
    assertEquals(0, channel.sends());
  }
}
