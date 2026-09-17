package com.shelfj.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.ids.Ids;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Notifier delivers once per (eventId, type): it sends + records on success, skips when already
 * notified or when there's no recipient, and on a channel failure propagates without recording (so
 * the consumer loop retries). notification-svc has no mocking framework, so fakes stand in.
 */
class NotifierTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();

  private RecordingChannel channel;
  private OnceRepo repo;
  private Notifier notifier;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    notifier = new Notifier();
    notifier.channel = channel;
    notifier.repo = repo;
  }

  @Test
  void sendsAndRecordsOnFirstDelivery() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(1, channel.sends());
    assertEquals(1, repo.records);
    assertEquals(TENANT, channel.lastTenantId, "channel must receive the tenant for scoping");
  }

  @Test
  void skipsWhenAlreadyNotified() {
    repo.notified = true;
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }

  @Test
  void skipsWhenNoRecipient() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, null, "Hi", "body");

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }

  @Test
  void channelFailurePropagatesAndIsNotRecorded() {
    channel.fail = true;

    assertThrows(
        RuntimeException.class,
        () -> notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body"));
    assertEquals(0, repo.records, "a failed send must not be recorded (so the consumer retries)");
  }
}
