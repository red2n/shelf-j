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
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
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
  void duplicateRegisterIs409() {
    post("/auth/register", "{\"email\":\"dup@example.com\",\"password\":\"strongpass1\"}");
    Response dup =
        post("/auth/register", "{\"email\":\"dup@example.com\",\"password\":\"strongpass1\"}");
    assertThat(dup.getStatus(), is(409));
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
