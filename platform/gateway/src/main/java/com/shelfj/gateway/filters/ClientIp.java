package com.shelfj.gateway.filters;

import io.helidon.webserver.http.ServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Resolves the client identity used for throttling (rate limit, brute force).
 *
 * <p>The socket remote address is the only value an attacker cannot choose. {@code X-Forwarded-For}
 * / {@code X-Real-IP} are plain request headers — honouring them by default lets a client rotate
 * the header to dodge throttling (and bloat per-key state). They are used only when the operator
 * explicitly declares the gateway sits behind a trusted proxy that overwrites them ({@code
 * shelfj.gateway.trust-forwarded-headers=true}).
 */
final class ClientIp {

  private ClientIp() {}

  static String resolve(
      ContainerRequestContext ctx, ServerRequest serverRequest, boolean trustForwardedHeaders) {
    if (trustForwardedHeaders) {
      String xf = ctx.getHeaderString("X-Forwarded-For");
      if (xf != null && !xf.isBlank()) {
        return xf.split(",")[0].trim();
      }
      String xr = ctx.getHeaderString("X-Real-IP");
      if (xr != null && !xr.isBlank()) {
        return xr.trim();
      }
    }
    if (serverRequest != null) {
      try {
        return serverRequest.remotePeer().host();
      } catch (RuntimeException ignored) {
        // proxy out of request scope or peer unavailable — fall through to the shared key
      }
    }
    return "unknown";
  }
}
