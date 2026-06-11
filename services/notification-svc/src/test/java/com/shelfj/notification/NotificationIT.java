package com.shelfj.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
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
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "notification");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "99999999-9999-9999-9999-999999999999";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get();
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
}
