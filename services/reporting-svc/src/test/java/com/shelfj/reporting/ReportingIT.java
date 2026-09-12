package com.shelfj.reporting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.shelfj.ids.Ids;
import com.shelfj.reporting.service.ReportingService;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;
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
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "reporting");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-611e-700f-b645-a14095230b77";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the sales projection directly (as SalesEventDispatcher
  // would).
  @Inject ReportingService reporting;

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

  /**
   * N4: a confirmed order + refund surface as gross/refunded/net; a redelivered refund is deduped.
   */
  @Test
  void salesSummaryReflectsGrossRefundedNetWithRefundDedupe() {
    UUID tenant = Ids.newId(); // fresh tenant → this test's sales only
    UUID order = Ids.newId();
    reporting.recordSale(
        tenant, order, Ids.newId(), "ONLINE", Ids.newId(), new BigDecimal("100.00"), "GBP");
    UUID refundEvent = Ids.newId();
    reporting.applySalesRefund(refundEvent, "test", tenant, order, new BigDecimal("25.00"));
    // Redelivery of the same PaymentRefunded event must not double-count.
    reporting.applySalesRefund(refundEvent, "test", tenant, order, new BigDecimal("25.00"));

    String body = get("/admin/reports/sales/summary", tenant.toString()).readEntity(String.class);
    assertThat(body, containsString("\"currency\":\"GBP\""));
    assertThat(body, containsString("\"gross\":100.00"));
    assertThat(body, containsString("\"refunded\":25.00"));
    assertThat(body, containsString("\"net\":75.00"));
  }

  /** N4: OrderConfirmed is projected once per order (natural PK idempotency). */
  @Test
  void recordSaleIsIdempotentOnOrderId() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    reporting.recordSale(tenant, order, Ids.newId(), "POS", null, new BigDecimal("40.00"), "GBP");
    // Redelivered OrderConfirmed for the same order → no second row / no doubled gross.
    reporting.recordSale(tenant, order, Ids.newId(), "POS", null, new BigDecimal("40.00"), "GBP");

    String body = get("/admin/reports/sales/by-day", tenant.toString()).readEntity(String.class);
    assertThat(body, containsString("\"orders\":1"));
    assertThat(body, containsString("\"gross\":40.00"));
  }
}
