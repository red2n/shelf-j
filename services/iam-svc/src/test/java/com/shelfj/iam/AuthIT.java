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
}
