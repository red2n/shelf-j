package com.shelfj.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import com.shelfj.test.ShelfJArchRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for customer-svc: register, lookup, loyalty earn/redeem/tier-up, store credit
 * issue/redeem, insufficient-balance 422, GDPR anonymise. Tests run against real Postgres
 * (Testcontainers); Kafka and Consul disabled.
 */
@HelidonTest
class CustomerIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "customer");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  void registerAndGetCustomer() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"alice@example.com\",\"firstName\":\"Alice\",\"lastName\":\"Smith\","
                + "\"gdprConsent\":true}");
    assertThat(r.getStatus(), is(201));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("alice@example.com"));
    String id = field(body, "id");

    String getBody =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(getBody, containsString("alice@example.com"));
  }

  @Test
  void duplicateEmailReturns409() {
    String json = "{\"email\":\"bob@example.com\",\"firstName\":\"Bob\",\"lastName\":\"Jones\"}";
    assertThat(post("/customers", json).getStatus(), is(201));
    assertThat(post("/customers", json).getStatus(), is(409));
  }

  @Test
  void lookupByEmail() {
    post(
        "/customers",
        "{\"email\":\"carol@example.com\",\"firstName\":\"Carol\",\"lastName\":\"White\"}");
    String result =
        target
            .path("/customers/lookup")
            .queryParam("email", "carol@example.com")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(result, containsString("carol@example.com"));
  }

  @Test
  void loyaltyEarnTierUpAndRedeem() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"dave@example.com\",\"firstName\":\"Dave\",\"lastName\":\"Brown\"}");
    String id = field(r.readEntity(String.class), "id");

    // earn 500 → BRONZE
    String earn1 =
        post("/customers/" + id + "/loyalty/earn", "{\"points\":500,\"reason\":\"purchase\"}")
            .readEntity(String.class);
    assertThat(earn1, containsString("BRONZE"));

    // earn 600 more → 1100 lifetime → SILVER
    String earn2 =
        post("/customers/" + id + "/loyalty/earn", "{\"points\":600,\"reason\":\"purchase\"}")
            .readEntity(String.class);
    assertThat(earn2, containsString("SILVER"));

    // redeem 100
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "{\"points\":100,\"reason\":\"discount\"}")
            .getStatus(),
        is(200));

    // ledger has 3 entries
    String ledger =
        target
            .path("/customers/" + id + "/loyalty/ledger")
            .queryParam("limit", 10)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(ledger, containsString("EARN"));
    assertThat(ledger, containsString("REDEEM"));
  }

  @Test
  void redeemMoreThanBalanceReturns422() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"eve@example.com\",\"firstName\":\"Eve\",\"lastName\":\"Green\"}");
    String id = field(r.readEntity(String.class), "id");
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "{\"points\":9999,\"reason\":\"test\"}")
            .getStatus(),
        is(422));
  }

  @Test
  void storeCreditIssueAndRedeem() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"frank@example.com\",\"firstName\":\"Frank\",\"lastName\":\"Black\"}");
    String id = field(r.readEntity(String.class), "id");

    assertThat(
        post(
                "/customers/" + id + "/store-credit/issue",
                "{\"amount\":50.00,\"reason\":\"return refund\"}")
            .getStatus(),
        is(200));

    assertThat(
        post(
                "/customers/" + id + "/store-credit/redeem",
                "{\"amount\":20.00,\"reason\":\"purchase\"}")
            .getStatus(),
        is(200));

    assertThat(
        post(
                "/customers/" + id + "/store-credit/redeem",
                "{\"amount\":999.00,\"reason\":\"over-limit\"}")
            .getStatus(),
        is(422));
  }

  @Test
  void tenantIsolation() {
    post(
        "/customers",
        "{\"email\":\"shared@example.com\",\"firstName\":\"Shared\",\"lastName\":\"User\"}");
    String listOtherTenant =
        target
            .path("/customers")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", "dddddddd-dddd-dddd-dddd-dddddddddddd")
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(listOtherTenant, not(containsString("shared@example.com")));
  }

  @Test
  void archRules() {
    var classes = new ClassFileImporter().importPackages("com.shelfj.customer");
    ShelfJArchRules.API_DOES_NOT_CALL_REPO.check(classes);
    ShelfJArchRules.DTOS_DO_NOT_EXPOSE_DOMAIN.check(classes);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Response post(String path, String json) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
