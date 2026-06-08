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

  private Response post(String path, String json, String headerName, String headerValue) {
    var req = target.path(path).request();
    if (headerName != null) {
      req = req.header(headerName, headerValue);
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

    // create first store → default + auto DEFAULT zone
    Response storeResp =
        post(
            "/onboarding/stores", "{\"name\":\"Main\",\"code\":\"MAIN\"}", "X-Tenant-Id", tenantId);
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
            .get(String.class);
    assertThat(zones, containsString("DEFAULT"));

    // duplicate store code → 409
    Response dup =
        post("/onboarding/stores", "{\"name\":\"Dup\",\"code\":\"MAIN\"}", "X-Tenant-Id", tenantId);
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
    post("/onboarding/stores", "{\"name\":\"A-store\",\"code\":\"AST\"}", "X-Tenant-Id", tenantA);

    // tenant B sees no stores
    String listB =
        target.path("/admin/stores").request().header("X-Tenant-Id", TENANT_B).get(String.class);
    assertThat(listB, not(containsString("A-store")));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
