package com.shelfj.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

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
 * Errors on the wire are RFC 9457 problem details, and the description is OpenAPI 3.1: what a
 * service built on common-web sends, seen from outside. A validation failure and a refused login
 * both leave as {@code application/problem+json} with the standard members beside the envelope a
 * client read before; a response with data is plain JSON still; {@code /openapi} says 3.1 and
 * carries the Problem schema.
 */
@HelidonTest
class ProblemDetailsIT {

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
    return target
        .path(path)
        .request()
        .header("X-Request-Id", "req-problem-it")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("A validation failure leaves as problem details, the old envelope beside them")
  void aValidationFailureIsAProblem() {
    Response r = post("/auth/register", "{\"email\":\"not-an-email\",\"password\":\"short\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(400));
    assertThat(r.getHeaderString("Content-Type"), startsWith("application/problem+json"));
    assertThat(body, containsString("\"type\":\"urn:shelfj:problem:VALIDATION_FAILED\""));
    assertThat(body, containsString("\"title\":\"Validation failed\""));
    assertThat(body, containsString("\"status\":400"));
    assertThat(body, containsString("\"instance\":\"/auth/register\""));
    assertThat(body, containsString("\"code\":\"VALIDATION_FAILED\""));
    assertThat(body, containsString("\"requestId\":\"req-problem-it\""));
    assertThat("the envelope a client read before", body, containsString("\"error\":{"));
  }

  @Test
  @DisplayName("A refusal thrown by the service is a problem too, with its own code and status")
  void aServiceRefusalIsAProblem() {
    Response r =
        post("/auth/register", "{\"email\":\"pd@example.com\",\"password\":\"fourteen chars\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(400));
    assertThat(r.getHeaderString("Content-Type"), startsWith("application/problem+json"));
    assertThat(body, containsString("\"type\":\"urn:shelfj:problem:PASSWORD_TOO_SHORT\""));
    assertThat(body, containsString("\"title\":\"Password too short\""));

    Response login =
        post("/auth/login", "{\"email\":\"nobody@example.com\",\"password\":\"whatever it is\"}");
    String refused = login.readEntity(String.class);
    assertThat(refused, login.getStatus(), is(401));
    assertThat(login.getHeaderString("Content-Type"), startsWith("application/problem+json"));
    assertThat(refused, containsString("\"status\":401"));
    assertThat(refused, containsString("\"code\":\"INVALID_CREDENTIALS\""));
  }

  @Test
  @DisplayName("A response with data is the plain envelope: application/json, no problem members")
  void dataIsNotAProblem() {
    Response r =
        post(
            "/auth/register",
            "{\"email\":\"pd-ok@example.com\",\"password\":\"a phrase long enough\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    assertThat(r.getHeaderString("Content-Type"), startsWith("application/json"));
    assertThat(body, not(containsString("urn:shelfj:problem")));
  }

  @Test
  @DisplayName("The description is OpenAPI 3.1 and carries the Problem schema")
  void theDescriptionIsOpenApi31() {
    Response r = target.path("/openapi").request(MediaType.APPLICATION_JSON).get();
    String doc = r.readEntity(String.class);
    assertThat(r.getStatus(), is(200));
    assertThat(doc.replaceAll("\\s+", ""), containsString("\"openapi\":\"3.1"));
    assertThat(doc, containsString("\"Problem\""));
    assertThat(doc, containsString("application/problem+json"));
  }
}
