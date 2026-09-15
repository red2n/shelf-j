package com.shelfj.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jurisdiction rules: which laws reach which business, on which day, and that nobody can rewrite
 * them through the API.
 */
@HelidonTest
class ObligationIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "tenant");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String OBLIGATIONS = "/admin/tenant/obligations";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String onboard(String country, String currency) {
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"businessName\":\"Obligations "
                        + country
                        + " "
                        + Ids.newId()
                        + "\",\"country\":\""
                        + country
                        + "\",\"currency\":\""
                        + currency
                        + "\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    int i = body.indexOf("\"id\":\"") + 6;
    return body.substring(i, body.indexOf('"', i));
  }

  private Response read(String tenant, String roles, String country, String on) {
    var t = target.path(OBLIGATIONS);
    if (country != null) t = t.queryParam("country", country);
    if (on != null) t = t.queryParam("on", on);
    var b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    return b.get();
  }

  private String sheet(String tenant, String country, String on) {
    Response r = read(tenant, "OWNER", country, on);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  /** The one obligation object carrying this code. */
  private static String obligation(String body, String code) {
    int at = body.indexOf("\"code\":\"" + code + "\"");
    if (at < 0) throw new AssertionError(code + " not in " + body);
    return body.substring(body.lastIndexOf('{', at), body.indexOf('}', at) + 1);
  }

  @Test
  @DisplayName("A British business gets UK law, and no EU law made after the UK left")
  void aBritishBusinessIsNotBoundByEuLawMadeAfterItLeft() {
    String gb = onboard("GB", "GBP");
    // No country asked for: the business's own.
    String body = sheet(gb, null, "2026-09-14");
    assertThat(body, containsString("\"country\":\"GB\""));
    assertThat(obligation(body, "UK_GDPR"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "UNIT_PRICING"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "TOBACCO_BIRTH_COHORT"), containsString("\"status\":\"UPCOMING\""));
    assertThat(body, not(containsString("GPSR_ONLINE_OFFER")));
    assertThat(body, not(containsString("\"scope\":\"EU\"")));
    // While a member, EU law reached it, and the window says the day it stopped.
    String before = sheet(gb, null, "2019-06-01");
    assertThat(obligation(before, "GDPR"), containsString("\"effectiveTo\":\"2020-01-31\""));
    assertThat(obligation(before, "GDPR"), containsString("\"scope\":\"EU\""));
  }

  @Test
  @DisplayName(
      "A German business inherits EU law and its own; a Portuguese one does not get Germany's")
  void membersInheritTheRegimeAndKeepTheirOwn() {
    String de = onboard("DE", "EUR");
    String body = sheet(de, null, "2026-09-14");
    assertThat(obligation(body, "GPSR_ONLINE_OFFER"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(
        obligation(body, "CRA_VULNERABILITY_REPORTING"), containsString("\"status\":\"IN_FORCE\""));
    assertThat(obligation(body, "E_INVOICING_RECEIVE"), containsString("\"scope\":\"DE\""));
    assertThat(obligation(body, "E_INVOICING_ISSUE"), containsString("\"status\":\"UPCOMING\""));
    // Directive 98/6/EC art.3 (V12): a unit price is EU law too, not the UK's alone.
    assertThat(obligation(body, "UNIT_PRICING"), containsString("\"scope\":\"EU\""));
    assertThat(
        "in force first, then upcoming",
        body.indexOf("E_INVOICING_RECEIVE"),
        is(org.hamcrest.Matchers.lessThan(body.indexOf("E_INVOICING_ISSUE"))));

    String pt = sheet(de, "PT", "2026-09-14");
    assertThat(pt, containsString("\"code\":\"GDPR\""));
    assertThat(pt, containsString("CERTIFIED_BILLING"));
    assertThat(pt, not(containsString("FISCAL_TSE")));
  }

  @Test
  @DisplayName("The day decides: in force from its date, upcoming the day before, gone once ended")
  void theDayDecides() {
    String gb = onboard("GB", "GBP");
    assertThat(
        obligation(sheet(gb, "GB", "2026-12-31"), "TOBACCO_BIRTH_COHORT"),
        containsString("\"status\":\"UPCOMING\""));
    assertThat(
        obligation(sheet(gb, "GB", "2027-01-01"), "TOBACCO_BIRTH_COHORT"),
        containsString("\"status\":\"IN_FORCE\""));
    // After the UK left, the EU's GDPR row has ended for it and is not listed at all.
    assertThat(sheet(gb, "GB", "2020-02-01"), not(containsString("\"code\":\"GDPR\"")));
    // A country the table knows nothing about has no obligations, not an error.
    assertThat(sheet(gb, "US", "2026-09-14"), containsString("\"obligations\":[]"));
  }

  @Test
  @DisplayName("Every staff role reads the rules; a shopper and a caller with no role do not")
  void staffReadTheRules() {
    String gb = onboard("GB", "GBP");
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      assertThat(role, read(gb, role, null, null).getStatus(), is(200));
    }
    assertThat(read(gb, "CUSTOMER", null, null).getStatus(), is(403));
    assertThat(read(gb, null, null, null).getStatus(), is(403));
  }

  @Test
  @DisplayName("A country or a date that is not one is refused, and nothing writes a rule")
  void badInputAndNoWrites() {
    String gb = onboard("GB", "GBP");
    for (String bad : new String[] {"GBR", "ZZ", "g", "GB' OR '1'='1", "9".repeat(300)}) {
      Response r = read(gb, "OWNER", bad, null);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("COUNTRY_INVALID"));
    }
    for (String bad :
        new String[] {"tomorrow", "2026-02-30", "1850-01-01", "9999-01-01", "14/09/2026"}) {
      Response r = read(gb, "OWNER", "GB", bad);
      assertThat(bad, r.getStatus(), is(400));
      assertThat(bad, r.readEntity(String.class), containsString("OBLIGATION_DATE_INVALID"));
    }
    // A fresh builder per method: Jersey keeps a body set on a builder, and refuses it on DELETE.
    java.util.function.Supplier<jakarta.ws.rs.client.Invocation.Builder> owner =
        () ->
            target.path(OBLIGATIONS).request().header("X-Tenant-Id", gb).header("X-Roles", "OWNER");
    assertThat(owner.get().post(Entity.json("{}")).getStatus(), anyOf(is(404), is(405)));
    assertThat(owner.get().put(Entity.json("{}")).getStatus(), anyOf(is(404), is(405)));
    assertThat(owner.get().delete().getStatus(), anyOf(is(404), is(405)));
  }

  @Test
  @DisplayName("Twenty reads at once all answer, with the same rules")
  void concurrentReadsAgree() throws Exception {
    String de = onboard("DE", "EUR");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 20; i++) {
        futures.add(pool.submit(() -> sheet(de, null, "2026-09-14")));
      }
      java.util.Set<String> bodies = new java.util.HashSet<>();
      for (var f : futures) bodies.add(f.get());
      assertThat(bodies.size(), is(1));
    } finally {
      pool.shutdownNow();
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.shelfj.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
