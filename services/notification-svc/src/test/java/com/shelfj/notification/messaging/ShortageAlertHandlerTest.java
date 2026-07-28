package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.shelfj.notification.channel.NotificationChannel;
import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.notification.service.NotificationService;
import com.shelfj.notification.service.Notifier;
import com.shelfj.notification.service.NotifierTestSupport;
import jakarta.json.Json;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ShortageAlertHandler both records the alert row (existing behaviour) and pushes it through {@link
 * Notifier} — the device-facing channel (MQTT to POS/kiosk/platform console) picks this up when
 * {@code shelfj.notification.channel=mqtt}. The two are independently idempotent: a redelivery must
 * still retry a push that previously failed, even though the alert row is already there from the
 * first delivery attempt.
 */
class ShortageAlertHandlerTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID STORE = UUID.randomUUID();
  private static final UUID VARIANT = UUID.randomUUID();

  private static final class FakeNotificationService extends NotificationService {
    boolean recordResult = true;

    @Override
    public boolean recordShortageAlertOnce(
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal available,
        BigDecimal threshold,
        UUID eventId) {
      return recordResult;
    }
  }

  private static final class FakeChannel implements NotificationChannel {
    int sends;

    @Override
    public String name() {
      return "FAKE";
    }

    @Override
    public void send(UUID tenantId, String recipient, String subject, String body) {
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
        UUID eventId,
        String type,
        String channel,
        String recipient,
        String subject,
        String body,
        String status) {
      records++;
      notified = true;
    }
  }

  private FakeNotificationService service;
  private FakeChannel channel;
  private FakeRepo repo;
  private ShortageAlertHandler handler;

  @BeforeEach
  void setUp() {
    service = new FakeNotificationService();
    channel = new FakeChannel();
    repo = new FakeRepo();
    Notifier notifier = NotifierTestSupport.notifierOf(channel, repo);

    handler = new ShortageAlertHandler();
    handler.service = service;
    handler.notifier = notifier;
  }

  private String payload() {
    return Json.createObjectBuilder()
        .add("eventId", EVENT.toString())
        .add("tenantId", TENANT.toString())
        .add("storeId", STORE.toString())
        .add("variantId", VARIANT.toString())
        .add("available", 2)
        .add("threshold", 5)
        .build()
        .toString();
  }

  @Test
  void pushesToTheDeviceChannelOnFirstDelivery() {
    handler.handle(payload());

    assertEquals(1, channel.sends);
    assertEquals(1, repo.records);
  }

  @Test
  void redeliveryDoesNotPushTwice() {
    handler.handle(payload());
    handler.handle(payload());

    assertEquals(1, channel.sends, "second delivery of the same event must be a no-op push");
  }

  @Test
  void redeliveryAfterAlertRowAlreadyRecordedStillRetriesThePush() {
    // Simulates: first delivery wrote the shortage_alerts row but the push itself failed (so
    // notification_log has no record) — a Kafka redelivery must still attempt the push, even
    // though recordShortageAlertOnce now returns false (already inserted).
    service.recordResult = false;

    handler.handle(payload());

    assertEquals(1, channel.sends, "push must be retried independently of the alert-row dedupe");
    assertEquals(1, repo.records);
  }
}
