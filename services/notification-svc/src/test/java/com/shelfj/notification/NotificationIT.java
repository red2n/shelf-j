package com.shelfj.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.notification.repo.NotificationRepository;
import com.shelfj.notification.service.NotificationErasure;
import com.shelfj.notification.service.Notifier;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for notification-svc against real Postgres (Testcontainers): list shortage
 * alerts (empty initial state), tenant isolation. Kafka/Consul disabled.
 */
@HelidonTest
class NotificationIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "notification");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "99999999-9999-9999-9999-999999999999";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the delivery path directly (as the consumers would). The
  // active channel is the default LogChannel, so send() just logs — no mail server needed.
  @Inject Notifier notifier;
  @Inject NotificationRepository notifications;
  @Inject NotificationErasure erasure;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  @Test
  void listAlertsEmptyInitially() {
    Response r = get("/admin/notifications/shortage-alerts", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  @Test
  void tenantIsolation() {
    Response r1 = get("/admin/notifications/shortage-alerts", T);
    Response r2 = get("/admin/notifications/shortage-alerts", OTHER);
    assertThat(r1.getStatus(), is(200));
    assertThat(r2.getStatus(), is(200));
  }

  /** N1: a delivered notification is recorded, and a redelivered event is a no-op. */
  @Test
  void notifyOnceRecordsAndIsIdempotent() {
    UUID event = UUID.randomUUID();
    UUID tenant = UUID.fromString(T);
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(false));

    notifier.notifyOnce(event, "WELCOME", tenant, null, "kit@example.com", "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(true));

    // Redelivery of the same event: no exception, still exactly one record.
    notifier.notifyOnce(event, "WELCOME", tenant, null, "kit@example.com", "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(true));

    // The in-app feed surfaces it.
    String feed = get("/admin/notifications", T).readEntity(String.class);
    assertThat(feed.contains("kit@example.com"), is(true));
    assertThat(feed.contains("\"type\":\"WELCOME\""), is(true));
  }

  /** N1: no recipient → nothing recorded (e.g. a guest order or missing email). */
  @Test
  void noRecipientRecordsNothing() {
    UUID event = UUID.randomUUID();
    notifier.notifyOnce(event, "WELCOME", UUID.fromString(T), null, null, "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(false));
  }

  // ── SJ-D43: erasing what was sent to a person ─────────────────────────────

  /** Reads one logged message straight from the table: the feed hides nothing a test can trust. */
  private static String[] logged(UUID eventId, String type) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT recipient, subject, body, redacted_at FROM notification.notification_log"
                    + " WHERE event_id = ? AND type = ?")) {
      ps.setObject(1, eventId);
      ps.setString(2, type);
      try (var rs = ps.executeQuery()) {
        assertThat("logged " + type, rs.next(), is(true));
        return new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)};
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void erasingACustomerErasesTheMessagesThatShopSentThem() {
    UUID tenant = UUID.fromString(T);
    UUID customer = UUID.randomUUID();
    UUID sent = UUID.randomUUID();
    notifier.notifyOnce(
        sent,
        "ORDER_CONFIRMATION",
        tenant,
        customer,
        "chris@example.com",
        "Your order is confirmed",
        "Order for Chris Carter, 12 High Street");

    assertThat(erasure.customerErased(tenant, customer), is(1));

    String[] row = logged(sent, "ORDER_CONFIRMATION");
    assertThat(row[0], is("[erased]"));
    assertThat(row[1], is("[erased]"));
    assertThat(row[2], is(""));
    assertThat(row[3] != null, is(true));
    // The send is still accounted for, so a redelivered event does not send it again.
    assertThat(notifications.alreadyNotified(sent, "ORDER_CONFIRMATION"), is(true));
    // And erasing again touches nothing.
    assertThat(erasure.customerErased(tenant, customer), is(0));
  }

  @Test
  void anotherShopsMessagesAboutTheSameIdAreNotTouched() {
    UUID customer = UUID.randomUUID();
    UUID theirs = UUID.randomUUID();
    notifier.notifyOnce(
        theirs,
        "ORDER_CONFIRMATION",
        UUID.fromString(OTHER),
        customer,
        "chris@example.com",
        "Your order is confirmed",
        "body");

    assertThat(erasure.customerErased(UUID.fromString(T), customer), is(0));
    assertThat(logged(theirs, "ORDER_CONFIRMATION")[0], is("chris@example.com"));
  }

  @Test
  void deletingAnAccountErasesThePlatformsMessagesButNotAShops() {
    UUID user = UUID.randomUUID();
    UUID welcome = UUID.randomUUID();
    UUID shopMessage = UUID.randomUUID();
    notifier.notifyOnce(
        welcome, "WELCOME", null, user, "leaving@example.com", "Welcome to Shelf-J", "hi");
    notifier.notifyOnce(
        shopMessage,
        "ORDER_CONFIRMATION",
        UUID.fromString(T),
        user,
        "leaving@example.com",
        "Your order is confirmed",
        "body");

    assertThat(erasure.accountDeleted(user), is(1));

    assertThat(logged(welcome, "WELCOME")[0], is("[erased]"));
    // The shop holds its own records and erases them on its own request.
    assertThat(logged(shopMessage, "ORDER_CONFIRMATION")[0], is("leaving@example.com"));
  }

  @Test
  void aReceiptSentWithACustomerIdCanBeErased() {
    UUID customer = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"recipient\":\"chris@example.com\",\"subject\":\"Your receipt\","
                        + "\"body\":\"Thanks\",\"type\":\"POS_RECEIPT\",\"eventId\":\""
                        + eventId
                        + "\",\"customerId\":\""
                        + customer
                        + "\"}"));
    assertThat(r.getStatus(), is(202));

    assertThat(erasure.customerErased(UUID.fromString(T), customer), is(1));
    assertThat(logged(UUID.fromString(eventId), "POS_RECEIPT")[0], is("[erased]"));
  }

  @Test
  void aCustomerIdThatIsNotAUuidIsRejected() {
    Response r =
        target
            .path("/notifications/send")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"recipient\":\"a@b.com\",\"subject\":\"s\",\"body\":\"b\","
                        + "\"customerId\":\"not-a-uuid\"}"));
    assertThat(r.getStatus(), is(400));
  }
}
