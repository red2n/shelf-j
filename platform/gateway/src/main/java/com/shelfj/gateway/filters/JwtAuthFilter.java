package com.shelfj.gateway.filters;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.gateway.GatewayConfig;
import com.shelfj.web.HttpHeaders;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Security boundary: validates the inbound JWT, then stamps verified identity headers (X-Tenant-Id,
 * X-User-Id, X-Roles) onto the request before it reaches ProxyResource.
 *
 * <p>Any client-supplied copies of those headers are removed first, so downstream services can
 * trust that only the gateway sets them (golden rule #3).
 *
 * <p>Public paths (register / login / refresh) bypass JWT validation. All other /api/** paths
 * require a valid Bearer token.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 1)
public class JwtAuthFilter implements ContainerRequestFilter {

  /** Exact request paths (normalized, no leading/trailing slash) that do NOT require a token. */
  private static final Set<String> PUBLIC_PATHS =
      Set.of("api/iam-svc/auth/register", "api/iam-svc/auth/login", "api/iam-svc/auth/refresh");

  @Inject GatewayConfig config;

  private JWTVerifier verifier;

  @PostConstruct
  void init() {
    String secret = config.jwtSecret();
    if (secret == null || secret.trim().length() < 32) {
      throw new IllegalStateException(
          "shelfj.jwt.secret must be set and at least 32 characters; refusing to start with a"
              + " weak or missing JWT secret");
    }
    verifier = JWT.require(Algorithm.HMAC256(secret)).withIssuer(config.jwtIssuer()).build();
  }

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    // Always strip any client-supplied identity headers to prevent spoofing.
    ctx.getHeaders().remove(HttpHeaders.TENANT_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_ID);
    ctx.getHeaders().remove(HttpHeaders.ROLES);

    String path = ctx.getUriInfo().getPath();

    // Allow public auth paths without a token.
    if (isPublic(path)) {
      return;
    }

    String authHeader = ctx.getHeaderString("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      ctx.abortWith(unauthorized("Missing or malformed Authorization header"));
      return;
    }

    String token = authHeader.substring(7).trim();
    DecodedJWT jwt;
    try {
      jwt = verifier.verify(token);
    } catch (JWTVerificationException e) {
      ctx.abortWith(unauthorized("Invalid or expired token"));
      return;
    }

    // Stamp verified claims as trusted headers for downstream services.
    String userId = jwt.getSubject();
    String tenantId = jwt.getClaim("tenant").asString();
    List<String> roles = jwt.getClaim("roles").asList(String.class);

    if (userId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_ID, userId);
    }
    if (tenantId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenantId);
    }
    if (roles != null && !roles.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.ROLES, String.join(",", roles));
    }
  }

  private static boolean isPublic(String path) {
    // Exact match only — a substring match would let any URL that merely embeds a public
    // suffix (e.g. /api/x-svc/foo/iam-svc/auth/login) skip token validation.
    return PUBLIC_PATHS.contains(normalize(path));
  }

  private static String normalize(String path) {
    String p = path;
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    return p;
  }

  private static Response unauthorized(String message) {
    return Response.status(Response.Status.UNAUTHORIZED)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.shelfj.web.ApiResponse.error(com.shelfj.web.ErrorBody.of("UNAUTHORIZED", message)))
        .build();
  }
}
