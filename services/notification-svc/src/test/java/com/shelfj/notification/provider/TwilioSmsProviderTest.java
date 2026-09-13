package com.shelfj.notification.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Twilio provider against a fake at the other end: the request it makes, the id it reads, and
 * what a refusal and an outage become.
 */
class TwilioSmsProviderTest {

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();
  private final List<String> auths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private int status = 201;
  private String reply = "{\"sid\":\"SM123\",\"status\":\"queued\"}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          paths.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
          auths.add(ex.getRequestHeaders().getFirst("Authorization"));
          bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

  private TwilioSmsProvider provider() {
    return TwilioSmsProvider.forTest(
        "http://127.0.0.1:" + server.getAddress().getPort(), "ACxxx", "secret", "+441234567890");
  }

  @Test
  void postsTheMessageWithBasicAuthAndReadsTheSid() {
    assertEquals("SM123", provider().send("+447700900123", "Your order is ready"));
    assertEquals("POST /2010-04-01/Accounts/ACxxx/Messages.json", paths.get(0));
    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("ACxxx:secret".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, auths.get(0));
    assertEquals("To=%2B447700900123&From=%2B441234567890&Body=Your+order+is+ready", bodies.get(0));
  }

  @Test
  void aRefusalIsNotRetryableAndCarriesTwiliosCode() {
    status = 400;
    reply = "{\"code\":21211,\"message\":\"The 'To' number is not a valid phone number.\"}";
    ProviderException e = assertThrows(ProviderException.class, () -> provider().send("+1", "x"));
    assertEquals("SMS_REJECTED_21211", e.code());
    assertFalse(e.retryable());
    assertTrue(e.getMessage().contains("not a valid phone number"));
  }

  @Test
  void anOutageIsRetryable() {
    status = 503;
    reply = "{}";
    ProviderException e =
        assertThrows(ProviderException.class, () -> provider().send("+447700900123", "x"));
    assertEquals("SMS_PROVIDER_UNAVAILABLE", e.code());
    assertTrue(e.retryable());
  }

  @Test
  void unconfiguredIsSaidPlainly() {
    TwilioSmsProvider p = TwilioSmsProvider.forTest("http://127.0.0.1:1", "", "", "");
    assertFalse(p.isConfigured());
    assertEquals(
        "SMS_NOT_CONFIGURED",
        assertThrows(ProviderException.class, () -> p.send("+447700900123", "x")).code());
  }

  @Test
  void anUnreachableProviderIsRetryable() {
    server.stop(0);
    ProviderException e =
        assertThrows(ProviderException.class, () -> provider().send("+447700900123", "x"));
    assertEquals("SMS_PROVIDER_UNREACHABLE", e.code());
    assertTrue(e.retryable());
  }
}
