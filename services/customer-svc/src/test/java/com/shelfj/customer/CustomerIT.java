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
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "customer");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the loyalty accrual path directly (as the consumer would).
  @Inject com.shelfj.customer.service.CustomerService loyalty;

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
  void storeCreditConcurrentRedeemNeverDoubleSpends() throws Exception {
    Response r =
        post(
            "/customers",
            "{\"email\":\"grace@example.com\",\"firstName\":\"Grace\",\"lastName\":\"Hopper\"}");
    String id = field(r.readEntity(String.class), "id");

    // Fund exactly 100; then fire N concurrent redeems each draining the whole balance.
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":100.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    int threads = 8;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var ready = new java.util.concurrent.CountDownLatch(threads);
    var go = new java.util.concurrent.CountDownLatch(1);
    var ok = new java.util.concurrent.atomic.AtomicInteger();
    var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                int status =
                    post(
                            "/customers/" + id + "/store-credit/redeem",
                            "{\"amount\":100.00,\"reason\":\"race\"}")
                        .getStatus();
                if (status == 200) ok.incrementAndGet();
                return status;
              }));
    }
    ready.await();
    go.countDown(); // release all at once
    for (var f : futures) f.get();
    pool.shutdown();

    // With FOR UPDATE row locking, exactly one redeem of the full balance can win; the rest see a
    // zero balance and get 422. Without the lock this would allow multiple winners (double-spend).
    assertThat("only one full-balance redeem may succeed", ok.get(), is(1));

    String credit =
        target
            .path("/customers/" + id + "/store-credit")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(credit, containsString("\"balance\":0"));
  }

  @Test
  void registerWithJsonBreakingEmailDoesNotCorruptEvent() {
    // A quoted-local-part email contains a double-quote that would break a string-concatenated JSON
    // payload (and used to be able to inject into the outbox event). The event is now built with a
    // JSON writer, so the only valid outcomes are: 201 (accepted and serialised safely) or 400
    // (bean-validation rejected the address up front) — never a 500 from a corrupted payload.
    int status =
        post(
                "/customers",
                "{\"email\":\"\\\"weird\\\"@example.com\",\"firstName\":\"Q\",\"lastName\":\"Q\"}")
            .getStatus();
    assertThat(status, org.hamcrest.Matchers.anyOf(is(201), is(400)));
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
  void updateAfterAnonymizeIsRejectedNotResurrected() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"helen@example.com\",\"firstName\":\"Helen\",\"lastName\":\"Lee\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    Response anonymized = delete("/customers/" + id);
    assertThat(anonymized.getStatus(), is(204));

    // A profile update after anonymize must be rejected, not silently resurrect the erased PII.
    Response updated =
        put("/customers/" + id, "{\"firstName\":\"Resurrected\",\"lastName\":\"Person\"}");
    assertThat(updated.getStatus(), is(409));
    assertThat(updated.readEntity(String.class), containsString("CUSTOMER_ANONYMIZED"));

    String getBody =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(getBody, not(containsString("Resurrected")));
    assertThat(getBody, containsString("ANONYMIZED"));
  }

  @Test
  void anonymizeRequiresManagementRole() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"ivy@example.com\",\"firstName\":\"Ivy\",\"lastName\":\"Nguyen\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    // GDPR erasure is destructive — unlike loyalty/store-credit redemption, a CASHIER must not be
    // able to perform it just by virtue of holding any staff role.
    Response asCashier =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CASHIER")
            .delete();
    assertThat(asCashier.getStatus(), is(403));
  }

  @Test
  void loyaltyAccruesFromOrderOnceAndDedupesOnEventId() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"jill@example.com\",\"firstName\":\"Jill\",\"lastName\":\"Reed\"}");
    String id = field(r.readEntity(String.class), "id");
    java.util.UUID tenant = java.util.UUID.fromString(TENANT);
    java.util.UUID customerId = java.util.UUID.fromString(id);
    java.util.UUID eventA = java.util.UUID.randomUUID();

    // Order A: £40 spent → 40 points at the default 1-point-per-unit rate.
    loyalty.accrueLoyaltyFromOrder(
        eventA, tenant, customerId, java.util.UUID.randomUUID(), new java.math.BigDecimal("40.00"));
    // Redelivery of the SAME event must not accrue again (dedupe on eventId).
    loyalty.accrueLoyaltyFromOrder(
        eventA, tenant, customerId, java.util.UUID.randomUUID(), new java.math.BigDecimal("40.00"));
    // A genuinely different order (new eventId) accrues normally → 50.
    loyalty.accrueLoyaltyFromOrder(
        java.util.UUID.randomUUID(),
        tenant,
        customerId,
        java.util.UUID.randomUUID(),
        new java.math.BigDecimal("10.00"));

    String acct =
        target
            .path("/customers/" + id + "/loyalty")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // 40 (once, not twice) + 10 = 50. Double-accrual would show 90.
    assertThat(acct, containsString("\"pointsBalance\":50.00"));
    assertThat(acct, not(containsString("\"pointsBalance\":90")));
  }

  @Test
  void storeCreditRedeemIsIdempotentPerOrder() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"kate@example.com\",\"firstName\":\"Kate\",\"lastName\":\"Ng\"}");
    String id = field(r.readEntity(String.class), "id");
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":100.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    // payment-svc may retry the same store-credit tender for an order; keyed on orderId, the second
    // redeem must be a no-op (not a second deduction).
    String order = java.util.UUID.randomUUID().toString();
    String body = "{\"amount\":30.00,\"orderId\":\"" + order + "\",\"reason\":\"tender\"}";
    assertThat(post("/customers/" + id + "/store-credit/redeem", body).getStatus(), is(200));
    assertThat(post("/customers/" + id + "/store-credit/redeem", body).getStatus(), is(200));

    String credit =
        target
            .path("/customers/" + id + "/store-credit")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // 100 − 30 (once, not twice) = 70. A double redeem would show 40.
    assertThat(credit, containsString("\"balance\":70.00"));
    assertThat(credit, not(containsString("\"balance\":40")));
  }

  @Test
  void customerReadsAreObjectLevelAuthorized() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"liam@example.com\",\"firstName\":\"Liam\",\"lastName\":\"Ortiz\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    // Wire up loyalty/store-credit balances so the reads below have something to check.
    assertThat(
        post("/customers/" + id + "/loyalty/earn", "{\"points\":10,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":5.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    // The customer themself (X-User-Id == the customer's own id) may read their own record.
    assertThat(getAs("/customers/" + id, id, "CUSTOMER").getStatus(), is(200));
    assertThat(getAs("/customers/" + id + "/addresses", id, "CUSTOMER").getStatus(), is(200));
    assertThat(getAs("/customers/" + id + "/loyalty", id, "CUSTOMER").getStatus(), is(200));
    assertThat(getAs("/customers/" + id + "/loyalty/ledger", id, "CUSTOMER").getStatus(), is(200));
    assertThat(getAs("/customers/" + id + "/store-credit", id, "CUSTOMER").getStatus(), is(200));

    // A different authenticated customer in the same tenant gets 404 (not 403 — no existence
    // oracle), even though the id is otherwise a valid path parameter.
    String otherCustomer = java.util.UUID.randomUUID().toString();
    assertThat(getAs("/customers/" + id, otherCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(
        getAs("/customers/" + id + "/addresses", otherCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(
        getAs("/customers/" + id + "/loyalty", otherCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(
        getAs("/customers/" + id + "/loyalty/ledger", otherCustomer, "CUSTOMER").getStatus(),
        is(404));
    assertThat(
        getAs("/customers/" + id + "/store-credit", otherCustomer, "CUSTOMER").getStatus(),
        is(404));

    // Staff read any customer in their tenant.
    assertThat(
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CASHIER")
            .get()
            .getStatus(),
        is(200));

    // A service-to-service lookup (X-Tenant-Id only, no principal) keeps working — notification-svc
    // resolves emails and payment-svc redeems store credit through this exact shape.
    Response s2s = target.path("/customers/" + id).request().header("X-Tenant-Id", TENANT).get();
    assertThat(s2s.getStatus(), is(200));
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

  private Response put(String path, String json) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response delete(String path) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .delete();
  }

  private Response getAs(String path, String userId, String roles) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", userId)
        .header("X-Roles", roles)
        .get();
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
