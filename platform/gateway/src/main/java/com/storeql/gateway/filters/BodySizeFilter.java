package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps request bodies to 1 MB at the one public door, except on the routes that take a document.
 *
 * <p>The server's own payload limit is one number for every route, and the gateway buffers a whole
 * body before forwarding it, so the limit had been set to what a JSON request needs. A supplier's
 * e-invoice is not JSON: a Factur-X PDF may be 20 MB (07.13). Raising the server's limit for that
 * one route would have raised it for every route, so the server limit is now the largest a named
 * upload route accepts, and this filter holds every other route to the smaller cap — refusing on
 * the declared length before a byte is read, and counting a chunked body only as far as its cap. It
 * runs after authentication, so nobody unauthenticated is read at all, and before the card-data
 * guard reads the body.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 40)
public class BodySizeFilter implements ContainerRequestFilter {

  static final String CODE = "PAYLOAD_TOO_LARGE";

  @Inject GatewayConfig config;

  private volatile String parsedFrom;
  private volatile Map<String, Long> routes = Map.of();

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!hasBody(requestContext.getMethod())) return;
    long limit = limitFor(requestContext.getUriInfo().getRequestUri());
    String declared = requestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH);
    if (declared != null && !declared.isBlank()) {
      long length;
      try {
        length = Long.parseLong(declared.strip());
      } catch (NumberFormatException e) {
        requestContext.abortWith(
            error(
                Response.Status.BAD_REQUEST,
                "BAD_CONTENT_LENGTH",
                "Content-Length is not a number"));
        return;
      }
      if (length > limit) refuse(requestContext, limit);
      return;
    }
    if (!requestContext.hasEntity()) return;
    byte[] head;
    try (InputStream in = requestContext.getEntityStream()) {
      head = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8L));
    }
    if (head.length > limit) {
      refuse(requestContext, limit);
    } else {
      requestContext.setEntityStream(new ByteArrayInputStream(head));
    }
  }

  /** The cap for a path: a named upload route's, or the default. */
  long limitFor(URI uri) {
    String path =
        uri == null || uri.getPath() == null
            ? ""
            : URI.create(uri.getRawPath()).normalize().getPath();
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    Long route = routes().get(path.toLowerCase(Locale.ROOT));
    return route != null ? route : Math.max(config.maxBodyBytes(), 0);
  }

  private Map<String, Long> routes() {
    String csv = config.uploadRoutes();
    if (csv == null) return Map.of();
    if (!csv.equals(parsedFrom)) {
      routes = parseRoutes(csv);
      parsedFrom = csv;
    }
    return routes;
  }

  /** {@code /api/purchase-svc/e-invoices=21000000,…}; an entry that does not parse is skipped. */
  static Map<String, Long> parseRoutes(String csv) {
    Map<String, Long> out = new HashMap<>();
    for (String entry : csv.split(",")) {
      int eq = entry.indexOf('=');
      if (eq <= 0) continue;
      String path = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
      long bytes = bytesOf(entry.substring(eq + 1));
      if (path.startsWith("/") && bytes > 0) out.put(path, bytes);
    }
    return Map.copyOf(out);
  }

  /** The byte count an entry gives, or -1 when it is not a number. */
  private static long bytesOf(String text) {
    try {
      return Long.parseLong(text.strip());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static boolean hasBody(String method) {
    String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m);
  }

  private static void refuse(ContainerRequestContext requestContext, long limit) {
    requestContext.abortWith(
        error(
            Response.Status.REQUEST_ENTITY_TOO_LARGE,
            CODE,
            "The request body is larger than this route accepts (" + limit + " bytes)"));
  }

  private static Response error(Response.Status status, String code, String message) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(ApiResponse.error(ErrorBody.of(code, message)))
        .build();
  }
}
