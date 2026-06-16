package com.shelfj.config;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guards every /config/** endpoint with a shared internal token.
 *
 * <p>Callers (business services) must supply the same token in the {@code X-Config-Token} request
 * header. The token is injected from the environment variable {@code SHELFJ_CONFIG_TOKEN} and must
 * be at least 32 characters. The service refuses to start if the token is absent or too short —
 * failing fast prevents accidentally running an open config endpoint in production.
 *
 * <p>Health-check paths ({@code /health}, {@code /metrics}) are exempted so probes keep working
 * without credentials.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class ConfigAuthFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-Config-Token";
  private static final int MIN_TOKEN_LENGTH = 32;

  private final String expectedToken;

  @Inject
  public ConfigAuthFilter(
      @ConfigProperty(name = "shelfj.config.token") String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "shelfj.config.token (SHELFJ_CONFIG_TOKEN) is not set. "
              + "The config service must not run without an internal auth token.");
    }
    if (token.length() < MIN_TOKEN_LENGTH) {
      throw new IllegalStateException(
          "shelfj.config.token must be at least "
              + MIN_TOKEN_LENGTH
              + " characters. Generate one with: openssl rand -base64 48");
    }
    this.expectedToken = token;
  }

  @Override
  public void filter(ContainerRequestContext ctx) {
    String path = ctx.getUriInfo().getPath();
    // Exempt liveness/readiness probes and metrics — they are polled by infrastructure.
    if (path.startsWith("health") || path.startsWith("metrics") || path.startsWith("observe")) {
      return;
    }

    String supplied = ctx.getHeaderString(TOKEN_HEADER);
    if (!expectedToken.equals(supplied)) {
      ctx.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity("{\"error\":\"CONFIG_UNAUTHORIZED\",\"message\":\"Missing or invalid X-Config-Token\"}")
              .type("application/json")
              .build());
    }
  }
}
