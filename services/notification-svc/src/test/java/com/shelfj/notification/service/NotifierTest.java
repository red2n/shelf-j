package com.shelfj.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.ids.Ids;
import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;
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

  private static final class FakeChannel implements NotificationChannel {
    int sends;
    boolean fail;
    UUID lastTenantId;

    @Override
    public String name() {
      return "FAKE";
    }

    @Override
    public void send(UUID tenantId, String recipient, String subject, String body) {
      if (fail) throw new IllegalStateException("boom");
      lastTenantId = tenantId;
      sends++;
    }
  }

  private static final class FakeRepo extends NotificationRepository {
    boolean notified;
    int records;

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
      records++;
    }
  }

  private FakeChannel channel;
  private FakeRepo repo;
  private Notifier notifier;

  @BeforeEach
  void setUp() {
    channel = new FakeChannel();
    repo = new FakeRepo();
    notifier = new Notifier();
    notifier.channel = channel;
    notifier.repo = repo;
  }

  @Test
  void sendsAndRecordsOnFirstDelivery() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(1, channel.sends);
    assertEquals(1, repo.records);
    assertEquals(TENANT, channel.lastTenantId, "channel must receive the tenant for scoping");
  }

  @Test
  void skipsWhenAlreadyNotified() {
    repo.notified = true;
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(0, channel.sends);
    assertEquals(0, repo.records);
  }

  @Test
  void skipsWhenNoRecipient() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, null, "Hi", "body");

    assertEquals(0, channel.sends);
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
