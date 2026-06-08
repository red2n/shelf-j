package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class BruteForceFilter implements ContainerRequestFilter, ContainerResponseFilter {

  @Inject GatewayConfig config;
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

    String ip = extractClientIp(requestContext);
    if (protection.isBlocked(ip)) {
      requestContext.abortWith(
          Response.status(429).entity("Too many failed login attempts - try later").build());
      return;
    }

    byte[] body;
    try (InputStream in = requestContext.getEntityStream()) {
      body = toByteArray(in);
    }
    if (body.length > 0) {
      try (Jsonb jsonb = JsonbBuilder.create()) {
        Map<String, Object> map =
            jsonb.fromJson(new String(body, StandardCharsets.UTF_8), Map.class);
        Object u = map.get("username");
        if (u != null) {
          requestContext.setProperty("login.username", u.toString());
        }
      } catch (Exception ignored) {
        // ignore parse errors; fallback to IP key only
      }
    }
    requestContext.setEntityStream(new ByteArrayInputStream(body));
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

    String username = (String) requestContext.getProperty("login.username");
    String key = username != null ? "user:" + username : extractClientIp(requestContext);

    int status = responseContext.getStatus();
    if (status == 401 || status == 403) {
      protection.recordFailure(key);
    } else if (status >= 200 && status < 300) {
      protection.recordSuccess(key);
    }
  }

  private boolean isLoginPath(String path) {
    if (path == null) {
      return false;
    }
    String normalizedPath = path.toLowerCase(Locale.ROOT);
    String loginPath = config.bruteForceLoginPath().toLowerCase(Locale.ROOT);
    return normalizedPath.contains(loginPath)
        || normalizedPath.endsWith("/login")
        || normalizedPath.endsWith("/authenticate");
  }

  private String extractClientIp(ContainerRequestContext ctx) {
    String xf = ctx.getHeaderString("X-Forwarded-For");
    if (xf != null && !xf.isBlank()) {
      return xf.split(",")[0].trim();
    }
    String xr = ctx.getHeaderString("X-Real-IP");
    if (xr != null && !xr.isBlank()) {
      return xr;
    }
    return "unknown";
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
