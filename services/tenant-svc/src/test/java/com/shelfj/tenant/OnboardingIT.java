package com.shelfj.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the tenant-svc onboarding flow against real Postgres (Testcontainers):
 * create tenant → create first store (auto DEFAULT zone) → tenant isolation → duplicate-code 409.
 * Kafka/Consul disabled; schema isolation off (the test container is private), so default schema is
 * fine.
 */
@HelidonTest
class OnboardingIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // Migrate into the test schema the app will use.
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "tenant");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String OWNER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String... headers) {
    var req = target.path(path).request();
    for (int i = 0; i < headers.length; i += 2) {
      req = req.header(headers[i], headers[i + 1]);
    }
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void fullOnboardingFlow() {
    // create tenant (owner identity from gateway header)
    Response tenantResp =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Acme\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            OWNER);
    assertThat(tenantResp.getStatus(), is(201));
    String tenantId = field(tenantResp.readEntity(String.class), "id");

    // create first store → default + auto DEFAULT zone (caller has OWNER by now — see RBAC filter)
    Response storeResp =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"MAIN\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(storeResp.getStatus(), is(201));
    String storeBody = storeResp.readEntity(String.class);
    assertThat(storeBody, containsString("\"isDefault\":true"));
    String storeId = field(storeBody, "id");

    // the DEFAULT zone exists
    String zones =
        target
            .path("/admin/stores/" + storeId + "/zones")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(zones, containsString("DEFAULT"));

    // duplicate store code → 409
    Response dup =
        post(
            "/onboarding/stores",
            "{\"name\":\"Dup\",\"code\":\"MAIN\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(dup.getStatus(), is(409));
  }

  @Test
  void createTenantRequiresUser() {
    Response noUser =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"X\",\"country\":\"in\",\"currency\":\"inr\"}",
            null,
            null);
    assertThat(noUser.getStatus(), is(401));
    assertThat(noUser.readEntity(String.class), containsString("NO_USER"));
  }

  @Test
  void tenantIsolationOnStores() {
    // tenant A creates a store
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"IsoCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            OWNER);
    String tenantA = field(t.readEntity(String.class), "id");
    post(
        "/onboarding/stores",
        "{\"name\":\"A-store\",\"code\":\"AST\"}",
        "X-Tenant-Id",
        tenantA,
        "X-Roles",
        "OWNER");

    // tenant B sees no stores
    String listB =
        target
            .path("/admin/stores")
            .request()
            .header("X-Tenant-Id", TENANT_B)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(listB, not(containsString("A-store")));
  }

  @Test
  void combinedOnboardCreatesTenatAndStore() {
    // single POST /onboarding creates tenant + first store atomically — no JWT refresh needed
    Response resp =
        post(
            "/onboarding",
            """
            {"businessName":"OneShot Co","country":"gb","currency":"gbp",\
            "storeName":"London HQ","storeCode":"LDN","storeCity":"London","storeCountry":"gb"}""",
            "X-User-Id",
            OWNER);
    assertThat(resp.getStatus(), is(201));
    String body = resp.readEntity(String.class);
    assertThat(body, containsString("\"name\":\"OneShot Co\""));
    assertThat(body, containsString("\"isDefault\":true"));
    assertThat(body, containsString("LDN"));
  }

  @Test
  void staffAssignment() {
    // create tenant + store
    Response tr =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"StaffCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            OWNER);
    String tenantId = field(tr.readEntity(String.class), "id");

    Response sr =
        post(
            "/onboarding/stores",
            "{\"name\":\"StaffStore\",\"code\":\"SS1\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    String storeId = field(sr.readEntity(String.class), "id");

    // assign a staff user
    String staffUserId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    Response assign =
        post(
            "/admin/staff",
            String.format(
                "{\"userId\":\"%s\",\"storeId\":\"%s\",\"role\":\"CASHIER\"}",
                staffUserId, storeId),
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(assign.getStatus(), is(201));

    // list staff — the assignment is visible
    String staffList =
        target
            .path("/admin/staff")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(staffList, containsString(staffUserId));
    assertThat(staffList, containsString("CASHIER"));

    // idempotency: posting the same assignment again → 409 (UNIQUE constraint)
    Response dup =
        post(
            "/admin/staff",
            String.format(
                "{\"userId\":\"%s\",\"storeId\":\"%s\",\"role\":\"CASHIER\"}",
                staffUserId, storeId),
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(dup.getStatus(), is(409));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
