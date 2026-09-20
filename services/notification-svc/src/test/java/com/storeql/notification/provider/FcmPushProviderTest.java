package com.storeql.notification.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The FCM provider against fakes for both endpoints: the service account's assertion is a real
 * RS256 JWT the fake verifies, the token is exchanged once and cached, the message has the shape
 * the v1 API takes, and a device FCM no longer knows comes back as UNREGISTERED.
 */
class FcmPushProviderTest {

  private HttpServer server;
  private KeyPair keys;
  private final List<String> paths = new ArrayList<>();
  private final List<String> auths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private int sendStatus = 200;
  private String sendReply = "{\"name\":\"projects/demo/messages/0:1\"}";

  @BeforeEach
  void start() throws Exception {
    keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String path = ex.getRequestURI().getPath();
          paths.add(ex.getRequestMethod() + " " + path);
          auths.add(ex.getRequestHeaders().getFirst("Authorization"));
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(body);
          int status;
          String reply;
          if (path.equals("/token")) {
            status = verifyAssertion(body) ? 200 : 401;
            reply =
                status == 200
                    ? "{\"access_token\":\"acc-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"
                    : "{\"error\":\"invalid_grant\"}";
          } else {
            status = sendStatus;
            reply = sendReply;
          }
          byte[] out = reply.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private String serviceAccount() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    return Json.createObjectBuilder()
        .add("type", "service_account")
        .add("project_id", "demo")
        .add("client_email", "fcm@demo.iam.gserviceaccount.com")
        .add("private_key", pem)
        .build()
        .toString();
  }

  /** The fake token endpoint checks the JWT the way Google would: RS256 over header.claims. */
  private boolean verifyAssertion(String form) {
    try {
      String assertion = null;
      for (String kv : form.split("&")) {
        if (kv.startsWith("assertion=")) assertion = kv.substring("assertion=".length());
      }
      if (assertion == null) return false;
      String[] parts = assertion.split("\\.");
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initVerify(keys.getPublic());
      sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) return false;
      JsonObject claims =
          Json.createReader(
                  new StringReader(
                      new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)))
              .readObject();
      return "fcm@demo.iam.gserviceaccount.com".equals(claims.getString("iss"))
          && claims.getString("scope").contains("firebase.messaging")
          && claims.getString("aud").equals(base() + "/token");
    } catch (Exception e) {
      return false;
    }
  }

  private FcmPushProvider provider() {
    return FcmPushProvider.forTest(base(), base() + "/token", "demo", serviceAccount());
  }

  @Test
  void signsAnAssertionExchangesItOnceAndSendsTheV1Message() {
    FcmPushProvider p = provider();
    assertTrue(p.isConfigured());
    String id = p.send("device-token-1", "Ready", "Collect it", Map.of("orderId", "o-1"));
    assertEquals("projects/demo/messages/0:1", id);
    assertEquals(List.of("POST /token", "POST /v1/projects/demo/messages:send"), paths);
    assertEquals("Bearer acc-1", auths.get(1));
    JsonObject msg =
        Json.createReader(new StringReader(bodies.get(1))).readObject().getJsonObject("message");
    assertEquals("device-token-1", msg.getString("token"));
    assertEquals("Ready", msg.getJsonObject("notification").getString("title"));
    assertEquals("o-1", msg.getJsonObject("data").getString("orderId"));

    // The token is cached: a second send does not go back for another.
    p.send("device-token-2", "Again", "b", Map.of());
    assertEquals(3, paths.size());
    assertEquals("POST /v1/projects/demo/messages:send", paths.get(2));
  }

  @Test
  void aDeviceFcmNoLongerKnowsIsUnregistered() {
    sendStatus = 404;
    sendReply =
        "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";
    ProviderException e =
        assertThrows(ProviderException.class, () -> provider().send("dead", "t", "b", Map.of()));
    assertEquals(ProviderException.UNREGISTERED, e.code());
    assertFalse(e.retryable());
  }

  @Test
  void anOutageIsRetryableAndARefusalIsNot() {
    sendStatus = 503;
    sendReply = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";
    assertTrue(
        assertThrows(ProviderException.class, () -> provider().send("d", "t", "b", Map.of()))
            .retryable());
    sendStatus = 400;
    sendReply = "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}";
    ProviderException e =
        assertThrows(ProviderException.class, () -> provider().send("d", "t", "b", Map.of()));
    assertEquals("PUSH_REJECTED_INVALID_ARGUMENT", e.code());
    assertFalse(e.retryable());
  }

  @Test
  void aBadServiceAccountIsRefusedAtLoad() {
    assertThrows(
        IllegalStateException.class,
        () ->
            FcmPushProvider.forTest(
                base(), base() + "/token", "demo", "{\"private_key\":\"not a key\"}"));
    FcmPushProvider unconfigured = new FcmPushProvider();
    assertFalse(unconfigured.isConfigured());
  }
}
