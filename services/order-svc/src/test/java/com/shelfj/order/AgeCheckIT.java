package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The due-diligence record behind age-restricted sales: what the till writes, what a manager can
 * read, and everything that must be refused — the wrong shape, the wrong store, the wrong role.
 */
@HelidonTest
class AgeCheckIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "order");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.order.erasure-sweeper.enabled", "false");
  }

  private static final String T = "01a090c9-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a090c9-1111-7000-8000-000000000002";
  private static final String STORE_A = "01a090c9-2222-7000-8000-00000000000a";
  private static final String STORE_B = "01a090c9-2222-7000-8000-00000000000b";
  private static final String VARIANT = "01a090c9-3333-7000-8000-000000000001";

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private Invocation.Builder as(
      String path, String tenant, String roles, String user, String stores) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b;
  }

  private static String body(String outcome, String extra) {
    return "{\"storeId\":\""
        + STORE_A
        + "\",\"variantId\":\""
        + VARIANT
        + "\",\"category\":\"ALCOHOL\",\"minimumAge\":18,\"country\":\"GB\","
        + "\"outcome\":\""
        + outcome
        + "\""
        + extra
        + "}";
  }

  private Response record(String roles, String stores, String json) {
    return as("/pos/age-checks", T, roles, Ids.newId().toString(), stores)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  @Test
  @DisplayName("A cashier records a refusal with its reason, and a pass with what was shown")
  void aCashierRecordsBothOutcomes() {
    Response refused = record("CASHIER", STORE_A, body("REFUSED", ",\"reason\":\"NO_ID\""));
    assertThat(refused.getStatus(), is(201));
    String r = refused.readEntity(String.class);
    assertThat(r, containsString("\"outcome\":\"REFUSED\""));
    assertThat(r, containsString("\"reason\":\"NO_ID\""));
    assertThat("the cashier comes from the token", r, containsString("\"cashierId\":\""));

    Response passed = record("CASHIER", STORE_A, body("PASSED", ",\"idType\":\"driving_licence\""));
    assertThat(passed.getStatus(), is(201));
    assertThat(passed.readEntity(String.class), containsString("\"idType\":\"DRIVING_LICENCE\""));
  }

  @Test
  @DisplayName("A refusal without a reason is not a record, and a pass cannot carry one")
  void theShapeIsEnforced() {
    Response noReason = record("CASHIER", STORE_A, body("REFUSED", ""));
    assertThat(noReason.getStatus(), is(400));
    assertThat(noReason.readEntity(String.class), containsString("AGE_CHECK_REASON_REQUIRED"));

    Response passWithReason = record("CASHIER", STORE_A, body("PASSED", ",\"reason\":\"NO_ID\""));
    assertThat(passWithReason.getStatus(), is(400));
    assertThat(passWithReason.readEntity(String.class), containsString("AGE_CHECK_REASON_ON_PASS"));

    Response idOnRefusal =
        record(
            "CASHIER", STORE_A, body("REFUSED", ",\"reason\":\"NO_ID\",\"idType\":\"PASSPORT\""));
    assertThat(idOnRefusal.getStatus(), is(400));
    assertThat(
        idOnRefusal.readEntity(String.class), containsString("AGE_CHECK_ID_TYPE_ON_REFUSAL"));

    assertThat(record("CASHIER", STORE_A, body("MAYBE", "")).getStatus(), is(400));
    assertThat(
        record("CASHIER", STORE_A, body("REFUSED", ",\"reason\":\"FELT_LIKE_IT\"")).getStatus(),
        is(400));
    assertThat(
        record("CASHIER", STORE_A, body("PASSED", ",\"idType\":\"LIBRARY_CARD\"")).getStatus(),
        is(400));
    // Bean validation: a country that is not two letters, an age outside 1..99.
    assertThat(
        record("CASHIER", STORE_A, body("PASSED", "").replace("\"GB\"", "\"GBR\"")).getStatus(),
        is(400));
    assertThat(
        record(
                "CASHIER",
                STORE_A,
                body("PASSED", "").replace("\"minimumAge\":18", "\"minimumAge\":0"))
            .getStatus(),
        is(400));
  }

  @Test
  @DisplayName("A cashier records checks at their own store only; a shopper records none")
  void theWrongCallerIsRefused() {
    // Scoped to store B, recording at store A.
    Response otherStore = record("CASHIER", STORE_B, body("REFUSED", ",\"reason\":\"UNDER_AGE\""));
    assertThat(otherStore.getStatus(), is(403));
    assertThat(otherStore.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    // An unrestricted role (no X-Store-Ids) records anywhere in the tenant.
    assertThat(
        record("MANAGER", null, body("REFUSED", ",\"reason\":\"UNDER_AGE\"")).getStatus(), is(201));
    // A customer token is not staff.
    assertThat(
        record("CUSTOMER", null, body("REFUSED", ",\"reason\":\"UNDER_AGE\"")).getStatus(),
        is(403));
    // No role at all.
    assertThat(
        record(null, null, body("REFUSED", ",\"reason\":\"UNDER_AGE\"")).getStatus(), is(403));
  }

  @Test
  @DisplayName("A store another tenant owns is refused, whatever the caller's role at home")
  void anotherTenantsStoreIsRefused() throws Exception {
    // The projection knows this store belongs to the other tenant.
    String theirs = Ids.newId().toString();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO \"order\".store_status (store_id, tenant_id, status, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now())")) {
      ps.setObject(1, UUID.fromString(theirs));
      ps.setObject(2, UUID.fromString(OTHER_T));
      ps.executeUpdate();
    }
    Response r =
        record("OWNER", null, body("REFUSED", ",\"reason\":\"NO_ID\"").replace(STORE_A, theirs));
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("STORE_NOT_OPERATIONAL"));
  }

  @Test
  @DisplayName("The register is management-only, tenant-scoped, filterable and paged")
  void theRegisterIsReadByManagement() {
    for (int i = 0; i < 3; i++) {
      assertThat(
          record("CASHIER", STORE_A, body("REFUSED", ",\"reason\":\"UNDER_AGE\"")).getStatus(),
          is(201));
    }
    assertThat(record("CASHIER", STORE_A, body("PASSED", "")).getStatus(), is(201));

    // A cashier cannot read the register; a manager can.
    assertThat(as("/admin/pos/age-checks", T, "CASHIER", null, null).get().getStatus(), is(403));
    Response page = as("/admin/pos/age-checks", T, "MANAGER", null, null).get();
    assertThat(page.getStatus(), is(200));
    assertThat(page.readEntity(String.class), containsString("\"outcome\":\"REFUSED\""));

    // Filtered to refusals at store A, two per page, with a cursor.
    Response p1 =
        target
            .path("/admin/pos/age-checks")
            .queryParam("store", STORE_A)
            .queryParam("outcome", "refused")
            .queryParam("limit", 2)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .get();
    String b1 = p1.readEntity(String.class);
    assertThat(p1.getStatus(), is(200));
    assertThat(b1, not(containsString("\"outcome\":\"PASSED\"")));
    assertThat("a next page exists", b1, containsString("\"nextCursor\":\""));
    String cursor = field(b1, "nextCursor");
    Response p2 =
        target
            .path("/admin/pos/age-checks")
            .queryParam("store", STORE_A)
            .queryParam("outcome", "REFUSED")
            .queryParam("limit", 2)
            .queryParam("after", cursor)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .get();
    assertThat(p2.getStatus(), is(200));

    // A malformed cursor, an unknown outcome and a bad date are 400s.
    assertThat(
        target
            .path("/admin/pos/age-checks")
            .queryParam("after", "!!")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .get()
            .getStatus(),
        is(400));
    assertThat(
        target
            .path("/admin/pos/age-checks")
            .queryParam("outcome", "SOMETIMES")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .get()
            .getStatus(),
        is(400));
    assertThat(
        target
            .path("/admin/pos/age-checks")
            .queryParam("from", "yesterday")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "MANAGER")
            .get()
            .getStatus(),
        is(400));

    // Another tenant sees nothing of ours.
    Response other = as("/admin/pos/age-checks", OTHER_T, "OWNER", null, null).get();
    assertThat(other.getStatus(), is(200));
    assertThat(other.readEntity(String.class), not(containsString(STORE_A)));
  }

  @Test
  @DisplayName("The summary counts refusals by reason and checks by category")
  void theSummaryCounts() {
    String store = Ids.newId().toString();
    String at = body("REFUSED", ",\"reason\":\"NO_ID\"").replace(STORE_A, store);
    assertThat(record("MANAGER", null, at).getStatus(), is(201));
    assertThat(record("MANAGER", null, at.replace("NO_ID", "UNDER_AGE")).getStatus(), is(201));
    assertThat(
        record(
                "MANAGER",
                null,
                body("PASSED", "").replace(STORE_A, store).replace("ALCOHOL", "TOBACCO"))
            .getStatus(),
        is(201));

    Response summary =
        target
            .path("/admin/pos/age-checks/summary")
            .queryParam("store", store)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    String s = summary.readEntity(String.class);
    assertThat(summary.getStatus(), is(200));
    assertThat(s, containsString("\"total\":3"));
    assertThat(s, containsString("\"passed\":1"));
    assertThat(s, containsString("\"refused\":2"));
    assertThat(s, containsString("\"NO_ID\":1"));
    assertThat(s, containsString("\"UNDER_AGE\":1"));
    assertThat(s, containsString("\"TOBACCO\":1"));
    assertThat(
        "cashiers do not read the summary",
        target
            .path("/admin/pos/age-checks/summary")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get()
            .getStatus(),
        is(403));
  }

  @Test
  @DisplayName(
      "The record is append-only: nothing updates or deletes it, and the row says what the rule was")
  void appendOnly() throws Exception {
    Response r = record("CASHIER", STORE_A, body("REFUSED", ",\"reason\":\"PROXY_SALE\""));
    String id = field(r.readEntity(String.class), "id");
    // No route exists to change or remove a record.
    assertThat(
        as("/pos/age-checks/" + id, T, "OWNER", null, null)
            .put(Entity.entity("{}", MediaType.APPLICATION_JSON))
            .getStatus(),
        is(404));
    assertThat(as("/pos/age-checks/" + id, T, "OWNER", null, null).delete().getStatus(), is(404));
    assertThat(
        as("/admin/pos/age-checks/" + id, T, "OWNER", null, null).delete().getStatus(), is(404));
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT category, minimum_age, country, reason FROM \"order\".age_verifications"
                    + " WHERE id = ?")) {
      ps.setObject(1, UUID.fromString(id));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next(), is(true));
        assertThat(rs.getString(1), is("ALCOHOL"));
        assertThat(rs.getInt(2), is(18));
        assertThat(rs.getString(3), is("GB"));
        assertThat(rs.getString(4), is("PROXY_SALE"));
      }
    }
  }
}
