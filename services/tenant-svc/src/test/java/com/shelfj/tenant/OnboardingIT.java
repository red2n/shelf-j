package com.shelfj.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
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

  private static final String OWNER = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String TENANT_B = "01a090ae-611e-7037-a4b7-c854f0266ace";

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
            "X-User-Id",
            OWNER,
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
            "X-User-Id",
            OWNER,
            "X-Roles",
            "OWNER");
    assertThat(dup.getStatus(), is(409));

    // onboarding status reflects the store that now exists
    String status =
        target
            .path("/onboarding/status")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", OWNER)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(status, containsString("\"hasDefaultStore\":true"));
  }

  /**
   * cart-svc, order-svc and iam-svc gate trading on a local store_status projection and let through
   * a store they have no row for. If creating a store did not announce its status, no row would
   * name the store's owner until its status first changed, and any tenant could open carts, POS
   * sessions and orders against the store's id.
   */
  @Test
  void creatingAStoreAnnouncesItsStatus() throws Exception {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Announce Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
            "X-User-Id",
            OWNER);
    String tenantId = field(t.readEntity(String.class), "id");
    Response first =
        post(
            "/onboarding/stores",
            "{\"name\":\"First\",\"code\":\"FIRST\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            OWNER,
            "X-Roles",
            "OWNER");
    Response second =
        post(
            "/admin/stores",
            "{\"name\":\"Second\",\"code\":\"SECOND\"}",
            "X-Tenant-Id",
            tenantId,
            "X-Roles",
            "OWNER");
    assertThat(first.getStatus(), is(201));
    assertThat(second.getStatus(), is(201));

    for (Response created : List.of(first, second)) {
      String storeId = field(created.readEntity(String.class), "id");
      List<String> announced = outboxPayloads(tenantId, "StoreStatusChanged", storeId);
      assertThat(announced, hasSize(1));
      assertThat(announced.get(0), containsString("\"tenantId\":\"" + tenantId + "\""));
      assertThat(announced.get(0), containsString("\"storeId\":\"" + storeId + "\""));
      assertThat(announced.get(0), containsString("\"status\":\"ACTIVE\""));
    }
  }

  /**
   * Regression test for the tenant-ownership check in TenantService#createDefaultStore /
   * #onboardingStatus: the gateway's onboarding carve-out (JwtAuthFilter#isOnboarding) forwards a
   * caller-supplied X-Tenant-Id whenever the caller's JWT has no tenant claim yet, precisely so a
   * user can name the tenant they just created — but that means tenantId alone is not proof of
   * authorization. A second user must not be able to reach into the first user's tenant just by
   * knowing its id.
   */
  @Test
  void onboardingStoresRejectsNonOwner() {
    Response t =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"VictimCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            OWNER);
    String tenantId = field(t.readEntity(String.class), "id");

    String attacker = "01a090ae-611e-7056-8f30-ecdbb48160eb";
    Response attackerStore =
        post(
            "/onboarding/stores",
            "{\"name\":\"Evil\",\"code\":\"EVIL1\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            attacker,
            "X-Roles",
            "OWNER");
    assertThat(attackerStore.getStatus(), is(403));
    assertThat(attackerStore.readEntity(String.class), containsString("TENANT_ACCESS_DENIED"));

    Response attackerStatus =
        target
            .path("/onboarding/status")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-User-Id", attacker)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(attackerStatus.getStatus(), is(403));
    assertThat(attackerStatus.readEntity(String.class), containsString("TENANT_ACCESS_DENIED"));

    // the real owner is unaffected
    Response ownerStore =
        post(
            "/onboarding/stores",
            "{\"name\":\"Main\",\"code\":\"MAIN\"}",
            "X-Tenant-Id",
            tenantId,
            "X-User-Id",
            OWNER,
            "X-Roles",
            "OWNER");
    assertThat(ownerStore.getStatus(), is(201));
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
        "X-User-Id",
        OWNER,
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
            "X-User-Id",
            OWNER,
            "X-Roles",
            "OWNER");
    String storeId = field(sr.readEntity(String.class), "id");

    // assign a staff user
    String staffUserId = "01a090ae-611e-703c-a378-a4972ea461c8";
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

  @Test
  void adminListsAreCursorPaginated() {
    Response tr =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"PageCo\",\"country\":\"in\",\"currency\":\"inr\"}",
            "X-User-Id",
            OWNER);
    String tenantId = field(tr.readEntity(String.class), "id");

    // 5 stores: the first via onboarding (default), the rest via the admin endpoint.
    post(
        "/onboarding/stores",
        "{\"name\":\"Page Store 1\",\"code\":\"PG1\"}",
        "X-Tenant-Id",
        tenantId,
        "X-User-Id",
        OWNER,
        "X-Roles",
        "OWNER");
    for (int i = 2; i <= 5; i++) {
      Response r =
          post(
              "/admin/stores",
              "{\"name\":\"Page Store " + i + "\",\"code\":\"PG" + i + "\"}",
              "X-Tenant-Id",
              tenantId,
              "X-Roles",
              "OWNER");
      assertThat(r.getStatus(), is(201));
    }

    // Walk /admin/stores with limit=2: pages of 2,2,1 and every store seen exactly once.
    java.util.Set<String> seen = new java.util.HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      WebTarget t = target.path("/admin/stores").queryParam("limit", 2);
      if (cursor != null) t = t.queryParam("after", cursor);
      String body =
          t.request().header("X-Tenant-Id", tenantId).header("X-Roles", "OWNER").get(String.class);
      pages++;
      for (int i = 1; i <= 5; i++) {
        String code = "\"code\":\"PG" + i + "\"";
        if (body.contains(code)) {
          assertThat("store PG" + i + " served twice", seen.add(code), is(true));
        }
      }
      int c = body.indexOf("\"nextCursor\":\"");
      cursor = c < 0 ? null : body.substring(c + 14, body.indexOf('"', c + 14));
    } while (cursor != null);
    assertThat(pages, is(3));
    assertThat(seen.size(), is(5));

    // A malformed cursor is rejected with 400 INVALID_CURSOR, not a 500.
    Response bad =
        target
            .path("/admin/stores")
            .queryParam("after", "not-base64-%%%")
            .request()
            .header("X-Tenant-Id", tenantId)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CURSOR"));
  }

  // ── SJ-D2 repair: re-announcing a tenant's declared currency ────────────────

  /**
   * order-svc stamps money onto orders using a currency it projects from TenantCreated. A tenant
   * onboarded before that consumer existed has no projection and silently trades in the platform
   * default, and TenantCreated cannot simply be replayed to fix it — iam-svc consumes it too, and
   * would re-run onboarding work. This endpoint re-announces just the currency.
   */
  @Test
  void republishCurrencyEmitsOneEventPerTenantWithACurrency() throws Exception {
    Response created =
        post(
            "/onboarding/tenants",
            "{\"businessName\":\"Replay Ltd\",\"country\":\"gb\",\"currency\":\"gbp\"}",
            "X-User-Id",
            OWNER);
    assertThat(created.getStatus(), is(201));
    String tenantId = field(created.readEntity(String.class), "id");

    int before = outboxCount("TenantCurrencyDeclared", tenantId);

    Response replay =
        target
            .path("/platform/tenants/republish-currency")
            .queryParam("tenantId", tenantId)
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(replay.getStatus(), is(200));
    assertThat(replay.readEntity(String.class), containsString("\"tenantsAnnounced\":1"));
    assertThat(outboxCount("TenantCurrencyDeclared", tenantId), is(before + 1));

    // Repeatable by design: consumers dedupe on eventId and each replay carries fresh ones, so a
    // second run re-applies the projection rather than being swallowed as a redelivery.
    target
        .path("/platform/tenants/republish-currency")
        .queryParam("tenantId", tenantId)
        .request()
        .header("X-Roles", "PLATFORM_ADMIN")
        .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(outboxCount("TenantCurrencyDeclared", tenantId), is(before + 2));

    // The payload has to carry the currency, or the consumer has nothing to project.
    assertThat(lastCurrencyEvent(tenantId), containsString("\"currency\":\"GBP\""));
  }

  /** Cross-tenant reach, so it is PLATFORM_ADMIN only — an OWNER must not be able to run it. */
  @Test
  void republishCurrencyIsPlatformAdminOnly() {
    Response asOwner =
        target
            .path("/platform/tenants/republish-currency")
            .request()
            .header("X-Tenant-Id", TENANT_B)
            .header("X-Roles", "OWNER")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(asOwner.getStatus(), is(403));
  }

  /** A tenantId that is not a UUID is the caller's mistake, so 400 rather than 500. */
  @Test
  void republishCurrencyRejectsAMalformedTenantId() {
    Response bad =
        target
            .path("/platform/tenants/republish-currency")
            .queryParam("tenantId", "not-a-uuid")
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_UUID"));
  }

  private static int outboxCount(String eventType, String tenantId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM tenant.outbox WHERE event_type = ? AND tenant_id = ?::uuid")) {
      ps.setString(1, eventType);
      ps.setString(2, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static List<String> outboxPayloads(String tenantId, String eventType, String aggregateId)
      throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + " AND aggregate_id = ?::uuid")) {
      ps.setString(1, tenantId);
      ps.setString(2, eventType);
      ps.setString(3, aggregateId);
      try (var rs = ps.executeQuery()) {
        List<String> payloads = new ArrayList<>();
        while (rs.next()) {
          payloads.add(rs.getString(1));
        }
        return payloads;
      }
    }
  }

  private static String lastCurrencyEvent(String tenantId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE event_type = 'TenantCurrencyDeclared'"
                    + " AND tenant_id = ?::uuid ORDER BY created_at DESC LIMIT 1")) {
      ps.setString(1, tenantId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
