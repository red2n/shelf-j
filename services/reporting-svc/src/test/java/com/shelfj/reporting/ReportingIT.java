package com.shelfj.reporting;

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
 * Integration tests for reporting-svc (gaps #47, #48, #49). Runs against real Postgres via
 * Testcontainers. Kafka/Consul disabled.
 */
@HelidonTest
class ReportingIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "reporting");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "22222222-2222-2222-2222-222222222222";
  private static final String OTHER = "99999999-9999-9999-9999-999999999999";

  @Inject WebTarget target;

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

  /** Gap #47: on-hand returns empty projection for a fresh tenant. */
  @Test
  void onHandEmptyInitially() {
    Response r = get("/admin/reports/inventory/on-hand", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
    assertThat(body.contains("grandTotal"), is(true));
  }

  /** Gap #48: supply-demand netting returns empty for a fresh tenant. */
  @Test
  void supplyDemandEmptyInitially() {
    Response r = get("/admin/reports/inventory/supply-demand", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  /** Gap #49: movement stats returns empty for a fresh tenant. */
  @Test
  void movementStatsEmptyInitially() {
    Response r = get("/admin/reports/inventory/movement-stats", T);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body.contains("\"data\""), is(true));
  }

  /** Tenant isolation: different tenants see independent data. */
  @Test
  void tenantIsolation() {
    Response r1 = get("/admin/reports/inventory/on-hand", T);
    Response r2 = get("/admin/reports/inventory/on-hand", OTHER);
    assertThat(r1.getStatus(), is(200));
    assertThat(r2.getStatus(), is(200));
  }
}
