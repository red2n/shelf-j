package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import io.helidon.webserver.http.ServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Locks out repeated failed logins, keyed BOTH per account and per client IP: the account key stops
 * a distributed (many-IP) attack on one user; the IP key stops one machine spraying many users.
 * Both keys are checked on the way in and both are recorded on the way out.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class BruteForceFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private static final String PROP_USER_KEY = "login.userKey";
  private static final String PROP_IP_KEY = "login.ipKey";

  /** Login bodies are tiny; anything bigger is not worth buffering for key extraction. */
  private static final int MAX_PARSEABLE_BODY_BYTES = 8 * 1024;

  @Inject GatewayConfig config;

  @Context ServerRequest serverRequest;

  private BruteForceProtectionService protection;

  @PostConstruct
  void init() {
    protection =
        new BruteForceProtectionService(
            config.bruteForceMaxFailures(),
            java.time.Duration.ofMinutes(config.bruteForceBlockMinutes()));
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.bruteForceEnabled()) {
      return;
    }

    String path = requestContext.getUriInfo().getPath();
    if (!isLoginPath(path) || !"POST".equalsIgnoreCase(requestContext.getMethod())) {
      return;
    }

    String ipKey = ClientIp.resolve(requestContext, serverRequest, config.trustForwardedHeaders());
    String userKey = extractUserKey(requestContext);
    requestContext.setProperty(PROP_IP_KEY, ipKey);
    if (userKey != null) {
      requestContext.setProperty(PROP_USER_KEY, userKey);
    }

    if (protection.isBlocked(ipKey) || protection.isBlocked(userKey)) {
      requestContext.abortWith(
          Response.status(429)
              .header("Retry-After", String.valueOf(config.bruteForceBlockMinutes() * 60L))
              .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
              .entity(
                  com.shelfj.web.ApiResponse.error(
                      com.shelfj.web.ErrorBody.of(
                          "LOGIN_LOCKED", "Too many failed login attempts - try later")))
              .build());
    }
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext)
      throws IOException {
    if (!config.bruteForceEnabled()) {
      return;
    }

    String path = requestContext.getUriInfo().getPath();
    if (!isLoginPath(path) || !"POST".equalsIgnoreCase(requestContext.getMethod())) {
      return;
    }

    String userKey = (String) requestContext.getProperty(PROP_USER_KEY);
    String ipKey = (String) requestContext.getProperty(PROP_IP_KEY);

    int status = responseContext.getStatus();
    if (status == 401 || status == 403) {
      protection.recordFailure(userKey);
      protection.recordFailure(ipKey);
    } else if (status >= 200 && status < 300) {
      protection.recordSuccess(userKey);
      protection.recordSuccess(ipKey);
    }
  }

  /**
   * Buffers the login body and pulls the account identifier — iam-svc logins carry {@code email};
   * {@code username} is kept for compatibility with other auth shapes. The stream is restored for
   * the proxy regardless.
   */
  private String extractUserKey(ContainerRequestContext requestContext) throws IOException {
    byte[] body;
    try (InputStream in = requestContext.getEntityStream()) {
      body = toByteArray(in);
    }
    requestContext.setEntityStream(new ByteArrayInputStream(body));
    if (body.length == 0 || body.length > MAX_PARSEABLE_BODY_BYTES) {
      return null;
    }
    try (Jsonb jsonb = JsonbBuilder.create()) {
      Map<String, Object> map = jsonb.fromJson(new String(body, StandardCharsets.UTF_8), Map.class);
      Object account = map.get("email");
      if (account == null) {
        account = map.get("username");
      }
      if (account != null && !account.toString().isBlank()) {
        return "user:" + account.toString().trim().toLowerCase(Locale.ROOT);
      }
    } catch (Exception ignored) {
      // unparseable body — IP key alone still protects
    }
    return null;
  }

  /**
   * Only credential-checking endpoints count. A broader match (e.g. contains "/auth") would treat
   * RBAC 403s on other auth-prefixed paths (POS session sweep, token admin) as failed logins and
   * lock out a legitimate client's IP.
   */
  private boolean isLoginPath(String path) {
    if (path == null) {
      return false;
    }
    String normalizedPath = path.toLowerCase(Locale.ROOT);
    while (normalizedPath.endsWith("/")) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    String loginPath = config.bruteForceLoginPath().toLowerCase(Locale.ROOT);
    return normalizedPath.endsWith(loginPath)
        || normalizedPath.endsWith("/login")
        || normalizedPath.endsWith("/authenticate");
  }

  private static byte[] toByteArray(InputStream in) throws IOException {
    if (in == null) {
      return new byte[0];
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int read = in.read(buf);
    while (read != -1) {
      out.write(buf, 0, read);
      read = in.read(buf);
    }
    return out.toByteArray();
  }
}
