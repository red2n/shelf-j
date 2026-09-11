package com.shelfj.iam;

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
 * Integration test for the iam-svc auth flow against a real Postgres (Testcontainers): register →
 * login → refresh (with single-use rotation) → /auth/me, plus validation and duplicate-detection.
 * Kafka/Consul disabled.
 */
@HelidonTest
class AuthIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    System.setProperty("shelfj.jwt.secret", "integration-test-secret-of-at-least-32-chars");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json) {
    return target.path(path).request().post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void registerLoginRefreshMe() {
    // register
    Response reg =
        post("/auth/register", "{\"email\":\"it-user@example.com\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String access = extract(regBody, "accessToken");
    String refresh = extract(regBody, "refreshToken");

    // me with the access token
    String me =
        target
            .path("/auth/me")
            .request()
            .header("Authorization", "Bearer " + access)
            .get(String.class);
    assertThat(me, containsString("it-user@example.com"));
    assertThat(me, containsString("CUSTOMER"));

    // login
    Response login =
        post("/auth/login", "{\"email\":\"it-user@example.com\",\"password\":\"strongpass1\"}");
    assertThat(login.getStatus(), is(200));

    // refresh rotates: old token then fails
    Response refreshed = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(refreshed.getStatus(), is(200));
    Response reuseOld = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(reuseOld.getStatus(), is(401));
  }

  @Test
  void wrongPasswordIs401() {
    post("/auth/register", "{\"email\":\"pw@example.com\",\"password\":\"correctpass1\"}");
    Response bad =
        post("/auth/login", "{\"email\":\"pw@example.com\",\"password\":\"wrongwrong\"}");
    assertThat(bad.getStatus(), is(401));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CREDENTIALS"));
  }

  @Test
  void disabledUserCannotLogIn() throws Exception {
    post("/auth/register", "{\"email\":\"disabled@example.com\",\"password\":\"correctpass1\"}");
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET status='DISABLED' WHERE lower(email)=?")) {
      ps.setString(1, "disabled@example.com");
      assertThat(ps.executeUpdate(), is(1));
    }
    // Right password, but the account is disabled — must still be rejected, not silently logged
    // in (and not via a different/faster code path that would leak the account's status by
    // timing — see AuthService.login()'s burn() call on the non-ACTIVE branch).
    Response login =
        post("/auth/login", "{\"email\":\"disabled@example.com\",\"password\":\"correctpass1\"}");
    assertThat(login.getStatus(), is(401));
    assertThat(login.readEntity(String.class), containsString("INVALID_CREDENTIALS"));
  }

  @Test
  void invalidInputIs400WithCleanEnvelope() {
    Response bad = post("/auth/register", "{\"email\":\"notanemail\",\"password\":\"short\"}");
    assertThat(bad.getStatus(), is(400));
    String body = bad.readEntity(String.class);
    assertThat(body, containsString("VALIDATION_FAILED"));
    assertThat(body, not(containsString("WeldSubclass"))); // no framework internals leaked
  }

  @Test
  void changePasswordRevokesOutstandingRefreshTokens() {
    Response reg =
        post("/auth/register", "{\"email\":\"rotate@example.com\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String access = extract(regBody, "accessToken");
    String refresh = extract(regBody, "refreshToken");

    String me =
        target
            .path("/auth/me")
            .request()
            .header("Authorization", "Bearer " + access)
            .get(String.class);
    String userId = extract(me, "userId");

    // change password (X-User-Id simulates the gateway-stamped identity header)
    Response changed =
        target
            .path("/auth/change-password")
            .request()
            .header("X-User-Id", userId)
            .put(
                Entity.entity(
                    "{\"currentPassword\":\"strongpass1\",\"newPassword\":\"evenstronger2\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(changed.getStatus(), is(200));

    // the pre-change refresh token must be dead
    Response reuse = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(reuse.getStatus(), is(401));

    // and the new password logs in
    Response login =
        post("/auth/login", "{\"email\":\"rotate@example.com\",\"password\":\"evenstronger2\"}");
    assertThat(login.getStatus(), is(200));
  }

  @Test
  void posSweepRequiresPlatformAdminRole() {
    // no identity headers → no roles → must be 403, not a tenant-wide sweep
    Response sweep =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweep.getStatus(), is(403));

    // a CUSTOMER (non-admin) must also be rejected
    Response sweepAsCustomer =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .header("X-Roles", "CUSTOMER")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweepAsCustomer.getStatus(), is(403));

    // PLATFORM_ADMIN passes the guard and executes (0 idle sessions → 200)
    Response sweepAsAdmin =
        target
            .path("/auth/pos/sessions/sweep")
            .request()
            .header("X-Roles", "PLATFORM_ADMIN")
            .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(sweepAsAdmin.getStatus(), is(200));
  }

  @Test
  void duplicateRegisterIs409() {
    post("/auth/register", "{\"email\":\"dup@example.com\",\"password\":\"strongpass1\"}");
    Response dup =
        post("/auth/register", "{\"email\":\"dup@example.com\",\"password\":\"strongpass1\"}");
    assertThat(dup.getStatus(), is(409));
  }

  @Test
  void suspendedTenantBlocksLoginAndRefresh() throws Exception {
    // Register a user, then bind it to a tenant and mark that tenant INACTIVE in iam's projection
    // (simulating the TenantStatusChanged event the consumer would apply).
    Response reg =
        post("/auth/register", "{\"email\":\"susp@example.com\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));
    String refresh = extract(reg.readEntity(String.class), "refreshToken");

    java.util.UUID tenantId = java.util.UUID.randomUUID();
    try (var c = iamConnection()) {
      try (var ps =
          c.prepareStatement(
              "UPDATE users SET tenant_id=?, type='STAFF' WHERE lower(email)=lower(?)")) {
        ps.setObject(1, tenantId);
        ps.setString(2, "susp@example.com");
        ps.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "INSERT INTO tenant_status (tenant_id, status, status_changed_at)"
                  + " VALUES (?, 'INACTIVE', now())")) {
        ps.setObject(1, tenantId);
        ps.executeUpdate();
      }
    }

    // Login is now forbidden for this tenant's staff, even with the correct password.
    Response blocked =
        post("/auth/login", "{\"email\":\"susp@example.com\",\"password\":\"strongpass1\"}");
    assertThat(blocked.getStatus(), is(403));
    assertThat(blocked.readEntity(String.class), containsString("TENANT_INACTIVE"));

    // An existing refresh token can't mint new access tokens either.
    Response refreshBlocked = post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}");
    assertThat(refreshBlocked.getStatus(), is(403));

    // Reactivating the tenant restores login.
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "UPDATE tenant_status SET status='ACTIVE', status_changed_at=now()"
                    + " WHERE tenant_id=?")) {
      ps.setObject(1, tenantId);
      ps.executeUpdate();
    }
    Response ok =
        post("/auth/login", "{\"email\":\"susp@example.com\",\"password\":\"strongpass1\"}");
    assertThat(ok.getStatus(), is(200));
  }

  @Test
  void provisionStaffRejectsBlankAndShortPassword() {
    java.util.UUID tenantId = java.util.UUID.randomUUID();
    Response blank =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-blankpw@example.com\",\"password\":\"\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(blank.getStatus(), is(400));
    assertThat(blank.readEntity(String.class), containsString("VALIDATION_FAILED"));

    Response tooShort =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-shortpw@example.com\",\"password\":\"short1\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(tooShort.getStatus(), is(400));
    assertThat(tooShort.readEntity(String.class), containsString("VALIDATION_FAILED"));
  }

  @Test
  void provisionStaffRequiresManagementRole() {
    java.util.UUID tenantId = java.util.UUID.randomUUID();
    // Asserted directly in AuthResource.provisionStaff as a backstop independent of the shared
    // filter's "/admin/" path-prefix rule — see its javadoc.
    Response asCashier =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "CASHIER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-forbidden@example.com\",\"password\":\"strongpass1\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(asCashier.getStatus(), is(403));
  }

  @Test
  void provisionStaffCreatesAccountWithoutRoleAssignment() throws Exception {
    // Regression: the old code passed "STAFF" as roleName to createUserWithOutbox, which called
    // roleIdByName("STAFF") — but "STAFF" is a user *type*, not a roles-table row, so every call
    // threw "role not found: STAFF" and returned 500. Now roleName is null → role assignment is
    // skipped, and the real store-scoped role arrives later via StaffAssigned event.
    java.util.UUID tenantId = java.util.UUID.randomUUID();
    Response resp =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-new@example.com\",\"password\":\"strongpass1\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(resp.getStatus(), is(200));
    String body = resp.readEntity(String.class);
    String userId = extract(body, "userId");

    // Verify no user_roles row was created — the account is intentionally role-less at this point.
    try (var c = iamConnection();
        var ps = c.prepareStatement("SELECT count(*) FROM user_roles WHERE user_id = ?")) {
      ps.setObject(1, java.util.UUID.fromString(userId));
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1), is(0));
      }
    }
  }

  @Test
  void provisionStaffIsIdempotentForSameEmail() {
    java.util.UUID tenantId = java.util.UUID.randomUUID();
    String body1 =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-idem@example.com\",\"password\":\"strongpass1\"}",
                    MediaType.APPLICATION_JSON))
            .readEntity(String.class);
    String body2 =
        target
            .path("/auth/admin/staff-users")
            .request()
            .header("X-Tenant-Id", tenantId.toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"staff-idem@example.com\",\"password\":\"strongpass1\"}",
                    MediaType.APPLICATION_JSON))
            .readEntity(String.class);
    assertThat(extract(body1, "userId"), is(extract(body2, "userId")));
  }

  /** A JDBC connection scoped to iam-svc's schema (the app uses shelfj.db.schema=iam). */
  private static java.sql.Connection iamConnection() throws java.sql.SQLException {
    var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  /** Tiny JSON field extractor (avoids pulling a JSON lib into the test). */
  private static String extract(String json, String field) {
    String key = "\"" + field + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) {
      throw new AssertionError("field " + field + " not in: " + json);
    }
    int start = i + key.length();
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  // ── SJ-D43: the account holder deletes their own login ─────────────────────

  private String registerAndGetUserId(String email) {
    Response reg =
        post("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));
    String access = extract(reg.readEntity(String.class), "accessToken");
    String me =
        target
            .path("/auth/me")
            .request()
            .header("Authorization", "Bearer " + access)
            .get(String.class);
    return extract(me, "userId");
  }

  private Response deleteAccount(String userId, String password) {
    return target
        .path("/auth/delete-account")
        .request()
        .header("X-User-Id", userId)
        .post(Entity.entity("{\"password\":\"" + password + "\"}", MediaType.APPLICATION_JSON));
  }

  @Test
  void aCustomerCanDeleteTheirOwnAccount() throws Exception {
    Response reg =
        post("/auth/register", "{\"email\":\"leaving@example.com\",\"password\":\"strongpass1\"}");
    assertThat(reg.getStatus(), is(201));
    String regBody = reg.readEntity(String.class);
    String refresh = extract(regBody, "refreshToken");
    String me =
        target
            .path("/auth/me")
            .request()
            .header("Authorization", "Bearer " + extract(regBody, "accessToken"))
            .get(String.class);
    String userId = extract(me, "userId");

    // A session left open on a shared device is not enough: the password is asked for again.
    assertThat(deleteAccount(userId, "not-my-password").getStatus(), is(401));
    assertThat(deleteAccount(userId, "strongpass1").getStatus(), is(200));

    // The login no longer works, and no session survives it.
    assertThat(
        post("/auth/login", "{\"email\":\"leaving@example.com\",\"password\":\"strongpass1\"}")
            .getStatus(),
        is(401));
    assertThat(
        post("/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}").getStatus(), is(401));

    // What identified the person is gone from the row; the row itself stays.
    try (var c = iamConnection();
        var ps =
            c.prepareStatement(
                "SELECT email, phone, password_hash, status FROM users WHERE id = ?")) {
      ps.setObject(1, java.util.UUID.fromString(userId));
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next(), is(true));
        assertThat(rs.getString("email"), org.hamcrest.Matchers.nullValue());
        assertThat(rs.getString("password_hash"), org.hamcrest.Matchers.nullValue());
        assertThat(rs.getString("status"), is("DELETED"));
      }
      // Other services are told, and the event carries no email.
      try (var ev =
          c.prepareStatement(
              "SELECT payload FROM outbox WHERE event_type = 'AccountDeleted' AND aggregate_id = ?")) {
        ev.setObject(1, java.util.UUID.fromString(userId));
        try (var rs = ev.executeQuery()) {
          assertThat(rs.next(), is(true));
          assertThat(rs.getString(1).contains("leaving@example.com"), is(false));
        }
      }
    }

    // The same address can open a new account later — a new one, not the old one back.
    Response again =
        post("/auth/register", "{\"email\":\"leaving@example.com\",\"password\":\"anotherpass2\"}");
    assertThat(again.getStatus(), is(201));
  }

  @Test
  void deletingTwiceIsRefusedNotRepeated() {
    String userId = registerAndGetUserId("twice@example.com");
    assertThat(deleteAccount(userId, "strongpass1").getStatus(), is(200));
    // The account is no longer active, so the same request cannot be verified a second time.
    assertThat(deleteAccount(userId, "strongpass1").getStatus(), is(401));
  }

  @Test
  void aStaffAccountIsNotDeletedHere() throws Exception {
    String userId = registerAndGetUserId("employee@example.com");
    try (var c = iamConnection();
        var ps = c.prepareStatement("UPDATE users SET type = 'STAFF' WHERE id = ?")) {
      ps.setObject(1, java.util.UUID.fromString(userId));
      ps.executeUpdate();
    }
    // A staff login belongs to the business that employs its holder, which removes it.
    Response r = deleteAccount(userId, "strongpass1");
    assertThat(r.getStatus(), is(403));
    assertThat(r.readEntity(String.class).contains("ACCOUNT_MANAGED_BY_EMPLOYER"), is(true));
  }
}
