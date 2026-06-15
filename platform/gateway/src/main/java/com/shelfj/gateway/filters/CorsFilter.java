package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Set;

/**
 * CORS for the future browser frontends (storefront / admin / POS). Allowed origins come from
 * {@code shelfj.gateway.cors.allowed-origins}; when that list is empty (the default) no CORS
 * headers are emitted at all, so browsers deny cross-origin calls until origins are configured
 * deliberately.
 *
 * <p>Runs {@code @PreMatching} at priority 50 — before rate limiting (100) and auth (999) — because
 * a preflight OPTIONS request carries no Authorization header and must be answered here, never
 * forwarded upstream.
 *
 * <p>A disallowed origin is not an error: the response simply carries no CORS headers and the
 * browser blocks it. Aborting with 403 would leak which origins are configured.
 */
@Provider
@PreMatching
@ApplicationScoped
@Priority(50)
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";
  private static final String ALLOWED_HEADERS =
      "Authorization, Content-Type, Idempotency-Key, X-Request-Id, X-Storefront-Tenant";
  private static final String EXPOSED_HEADERS = "X-Request-Id, Retry-After";
  private static final String MAX_AGE_SECONDS = "3600";

  @Inject GatewayConfig config;

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    String origin = ctx.getHeaderString("Origin");
    if (origin == null || origin.isBlank()) {
      return; // not a cross-origin browser request
    }
    boolean preflight =
        HttpMethod.OPTIONS.equals(ctx.getMethod())
            && ctx.getHeaderString("Access-Control-Request-Method") != null;
    if (!preflight) {
      return; // actual requests get their CORS headers in the response phase
    }
    Response.ResponseBuilder rb = Response.noContent();
    if (isAllowed(origin)) {
      rb.header("Access-Control-Allow-Origin", origin)
          .header("Access-Control-Allow-Methods", ALLOWED_METHODS)
          .header("Access-Control-Allow-Headers", ALLOWED_HEADERS)
          .header("Access-Control-Max-Age", MAX_AGE_SECONDS)
          .header("Vary", "Origin");
    }
    ctx.abortWith(rb.build());
  }

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
    String origin = req.getHeaderString("Origin");
    if (origin == null
        || origin.isBlank()
        || !isAllowed(origin)
        || res.getHeaders().containsKey("Access-Control-Allow-Origin")) {
      return;
    }
    res.getHeaders().add("Access-Control-Allow-Origin", origin);
    res.getHeaders().add("Access-Control-Expose-Headers", EXPOSED_HEADERS);
    res.getHeaders().add("Vary", "Origin");
  }

  private boolean isAllowed(String origin) {
    Set<String> allowed = config.corsAllowedOrigins();
    // The configured origin is echoed back rather than a literal "*" so responses stay valid
    // for credentialed requests.
    return allowed.contains("*") || allowed.contains(origin);
  }
}
