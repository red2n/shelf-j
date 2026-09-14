package com.shelfj.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.http.HeaderNames;
import io.helidon.http.WritableHeaders;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy relays a service's response headers by allowlist. A download keeps its file name and a
 * sensitive response its no-store rule; nothing else a service sets crosses the edge.
 */
class ProxyResourceRelayedHeadersTest {

  @Test
  @DisplayName("A download keeps its file name and its caching rule through the gateway")
  void aDownloadKeepsItsNameAndCachingRule() {
    var upstream = WritableHeaders.create();
    upstream.set(HeaderNames.create("Content-Disposition"), "attachment; filename=\"pay.csv\"");
    upstream.set(HeaderNames.CACHE_CONTROL, "no-store");
    var relayed = ProxyResource.relayedResponseHeaders(upstream);
    assertEquals("attachment; filename=\"pay.csv\"", relayed.get("Content-Disposition"));
    assertEquals("no-store", relayed.get("Cache-Control"));
    assertEquals(List.of("Content-Disposition", "Cache-Control"), List.copyOf(relayed.keySet()));
  }

  @Test
  @DisplayName("Cookies, the server banner and internal headers stay behind the gateway")
  void nothingElseCrossesTheEdge() {
    var upstream = WritableHeaders.create();
    upstream.set(HeaderNames.SET_COOKIE, "session=stolen; Path=/");
    upstream.set(HeaderNames.create("Server"), "Helidon 4");
    upstream.set(HeaderNames.create("X-Internal-Node"), "purchase-svc-7");
    upstream.set(HeaderNames.create("Access-Control-Allow-Origin"), "*");
    assertTrue(ProxyResource.relayedResponseHeaders(upstream).isEmpty());
    assertTrue(ProxyResource.relayedResponseHeaders(WritableHeaders.create()).isEmpty());
  }
}
