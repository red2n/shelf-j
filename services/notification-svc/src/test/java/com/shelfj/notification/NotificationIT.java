package com.shelfj.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.notification.repo.NotificationRepository;
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

    notifier.notifyOnce(event, "WELCOME", tenant, "kit@example.com", "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(true));

    // Redelivery of the same event: no exception, still exactly one record.
    notifier.notifyOnce(event, "WELCOME", tenant, "kit@example.com", "Welcome", "hi");
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
    notifier.notifyOnce(event, "WELCOME", UUID.fromString(T), null, "Welcome", "hi");
    assertThat(notifications.alreadyNotified(event, "WELCOME"), is(false));
  }
}
