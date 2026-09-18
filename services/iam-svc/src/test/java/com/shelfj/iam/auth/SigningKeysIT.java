package com.shelfj.iam.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.SigningKeysFixture;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Asymmetric token signing with key rotation (20.15). Tokens are RS256 and name their key; the
 * public halves are published for every verifier; a token that claims another algorithm, no
 * algorithm, or a key this service never published is refused; rotation keeps the old key verifying
 * while its tokens can live, then retires it and wipes its private half; only a platform
 * administrator rotates, and no route returns key material.
 */
@HelidonTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SigningKeysIT {

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
    // A rotated key is retired a second after it starts retiring, so the test can watch it go.
    System.setProperty("shelfj.jwt.retire-after-seconds", "1");
    // A rotated key signs two seconds after it is published, so the test can watch the hand-over.
    System.setProperty("shelfj.jwt.publish-lead-seconds", "2");
  }

  @Inject WebTarget target;
  @Inject JwtService jwt;
  @Inject SigningKeys keys;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String register(String email) {
    Response r =
        target
            .path("/auth/register")
            .request()
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"a phrase long enough\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("accessToken");
  }

  private JsonArray jwks() {
    Response r = target.path("/auth/.well-known/jwks.json").request().get();
    assertThat(r.getStatus(), is(200));
    assertThat(r.getHeaderString("Cache-Control"), containsString("max-age"));
    return Json.createReader(new StringReader(r.readEntity(String.class)))
        .readObject()
        .getJsonArray("keys");
  }

  private Response admin(String method, String path, String roles) {
    var b = target.path(path).request().header("X-User-Id", Ids.newId()).header("X-Roles", roles);
    return "POST".equals(method) ? b.post(Entity.json("")) : b.get();
  }

  @Test
  @Order(1)
  @DisplayName("A token is RS256, names its key, and the key is in the published set")
  void aTokenNamesAPublishedKey() {
    String token = register("keys-1@example.com");
    var decoded = JWT.decode(token);
    assertThat(decoded.getAlgorithm(), is("RS256"));
    JsonArray set = jwks();
    assertThat(set.size(), is(1));
    var key = set.getJsonObject(0);
    assertThat(key.getString("kid"), is(decoded.getKeyId()));
    assertThat(key.getString("kty"), is("RSA"));
    assertThat(key.getString("alg"), is("RS256"));
    assertThat(key.getString("use"), is("sig"));
    assertThat("no private parameter is ever published", key.containsKey("d"), is(false));
    assertThat(jwt.verify(token).getSubject(), is(decoded.getSubject()));
  }

  @Test
  @Order(2)
  @DisplayName(
      "Another algorithm, no algorithm, a stranger's key and a changed payload are refused")
  void whatIsNotOursIsRefused() {
    String token = register("keys-2@example.com");
    String kid = JWT.decode(token).getKeyId();
    String publicKeyBase64 =
        keys.published().stream()
            .filter(k -> k.kid().equals(kid))
            .findFirst()
            .orElseThrow()
            .publicKey();

    // Algorithm confusion: an HS256 token "signed" with the public key, which anyone can read.
    String confused =
        JWT.create()
            .withIssuer("shelfj")
            .withSubject(Ids.newId().toString())
            .withKeyId(kid)
            .withClaim("roles", java.util.List.of("PLATFORM_ADMIN"))
            .withExpiresAt(Instant.now().plusSeconds(300))
            .sign(Algorithm.HMAC256(Base64.getDecoder().decode(publicKeyBase64)));
    assertThrows(JWTVerificationException.class, () -> jwt.verify(confused));

    // No algorithm at all.
    String[] parts = token.split("\\.");
    String none =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"alg\":\"none\",\"kid\":\"" + kid + "\"}").getBytes())
            + "."
            + parts[1]
            + ".";
    assertThrows(JWTVerificationException.class, () -> jwt.verify(none));

    // A stranger's key under our key id.
    String forged =
        SigningKeysFixture.generate(kid)
            .sign(
                "shelfj", Ids.newId().toString(), Map.of("roles", java.util.List.of("OWNER")), 300);
    assertThrows(JWTVerificationException.class, () -> jwt.verify(forged));

    // Our signature over a payload that was changed afterwards.
    String tampered =
        parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
    assertThrows(JWTVerificationException.class, () -> jwt.verify(tampered));
  }

  @Test
  @Order(3)
  @DisplayName("Only a platform administrator rotates or lists, and nothing returns key material")
  void onlyThePlatformAdministratorRotates() {
    assertThat(admin("POST", "/auth/admin/signing-keys/rotate", "OWNER").getStatus(), is(403));
    assertThat(admin("GET", "/auth/admin/signing-keys", "MANAGER").getStatus(), is(403));
    assertThat(admin("POST", "/auth/admin/signing-keys/rotate", "CASHIER").getStatus(), is(403));
    Response list = admin("GET", "/auth/admin/signing-keys", "PLATFORM_ADMIN");
    String body = list.readEntity(String.class);
    assertThat(body, list.getStatus(), is(200));
    assertThat(body, containsString("\"status\":\"ACTIVE\""));
    assertThat(body, not(containsString("private")));
    assertThat(body, not(containsString("publicKey")));
  }

  @Test
  @Order(4)
  @DisplayName(
      "Rotation: the old key verifies while its tokens can live, then is retired and wiped")
  void rotationKeepsTheOldKeyUntilItsTokensAreGone() throws Exception {
    String before = register("keys-4@example.com");
    String oldKid = JWT.decode(before).getKeyId();

    Response rotated = admin("POST", "/auth/admin/signing-keys/rotate", "PLATFORM_ADMIN");
    String body = rotated.readEntity(String.class);
    assertThat(body, rotated.getStatus(), is(201));
    assertThat(body, containsString("\"status\":\"ACTIVE\""));

    String during = register("keys-4b@example.com");
    assertThat(
        "published first: the old key signs until verifiers have had time to read the new one",
        JWT.decode(during).getKeyId(),
        is(oldKid));
    assertThat("both keys are published from the moment of rotation", jwks().size(), is(2));

    // A second of margin over the two-second lead, not two hundred milliseconds. The lead is
    // compared
    // against wall-clock time, and this test runs inside a reactor build where a whole second can
    // disappear between the sleep ending and the request being served. A margin smaller than the
    // machine's own noise measures the machine and not the rule — the same lesson the chargeback
    // evidence-deadline check learned on the same day.
    Thread.sleep(3000);
    String after = register("keys-4c@example.com");
    String newKid = JWT.decode(after).getKeyId();
    assertThat("after the lead time the new key signs", newKid, not(is(oldKid)));
    assertThat(jwt.verify(before).getKeyId(), is(oldKid));
    assertThat(jwt.verify(after).getKeyId(), is(newKid));

    keys.maintain(Instant.now());
    assertThat("the retired key leaves the published set", jwks().size(), is(1));
    assertThrows(JWTVerificationException.class, () -> jwt.verify(before));
    assertThat(jwt.verify(after).getKeyId(), is(newKid));

    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT status, private_key_sealed, retired_at IS NOT NULL FROM iam.signing_keys"
                    + " WHERE kid = ?")) {
      ps.setString(1, oldKid);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next(), is(true));
        assertThat(rs.getString(1), is("RETIRED"));
        assertThat("the private half is wiped", rs.getString(2), is(""));
        assertThat(rs.getBoolean(3), is(true));
      }
    }
  }
}
