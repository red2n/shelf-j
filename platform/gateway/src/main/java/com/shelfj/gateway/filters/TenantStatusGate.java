package com.shelfj.gateway.filters;

import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.web.HttpHeaders;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storefront suspension gate. A deactivated tenant's online shop must stop serving — but the
 * gateway is stateless about tenant data, so it asks tenant-svc ({@code GET /storefront/active})
 * and caches the answer for a short TTL to keep the hot path fast.
 *
 * <p><strong>Fail-open:</strong> a lookup error (tenant-svc down, timeout) returns {@code active}.
 * A transient tenant-svc blip must not take every storefront offline; the hard block that matters
 * (staff login) is enforced in iam-svc independently.
 */
@ApplicationScoped
public class TenantStatusGate {

  private static final Logger LOG = System.getLogger(TenantStatusGate.class.getName());
  private static final long TTL_MILLIS = 60_000;

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;

  private record Cached(boolean active, long expiresAt) {}

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  /** True if the tenant may transact. Cached for {@value #TTL_MILLIS}ms; fails open on error. */
  public boolean isActive(String tenantId) {
    long now = System.currentTimeMillis();
    Cached c = cache.get(tenantId);
    if (c != null && c.expiresAt() > now) {
      return c.active();
    }
    boolean active = lookup(tenantId);
    cache.put(tenantId, new Cached(active, now + TTL_MILLIS));
    return active;
  }

  private boolean lookup(String tenantId) {
    var instance = registry.resolve("tenant-svc");
    if (instance.isEmpty()) {
      return true; // can't resolve tenant-svc → fail open
    }
    try (var resp =
        webClient
            .get(instance.get().baseUri() + "/storefront/active")
            .header(io.helidon.http.HeaderNames.create(HttpHeaders.TENANT_ID), tenantId)
            .request()) {
      if (resp.status().code() == 404) {
        return false; // tenant does not exist → reject, not fail-open
      }
      if (resp.status().code() != 200) {
        return true; // server error / unavailability → fail open (keep storefronts up)
      }
      String body = resp.as(String.class);
      // Inactive only on an explicit, successfully-read negative — otherwise fail open.
      return !body.replaceAll("\\s", "").contains("\"active\":false");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "tenant-status lookup failed for " + tenantId + ": " + e.getMessage());
      return true;
    }
  }
}
