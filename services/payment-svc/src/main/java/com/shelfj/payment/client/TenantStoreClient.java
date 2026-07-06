package com.shelfj.payment.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.payment.config.ServiceConfig;
import com.shelfj.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sync client for tenant-svc's public {@code GET /storefront/config?store=} — used to learn which
 * tenders the owner has enabled for a store (golden rule #1: store settings belong to tenant-svc).
 *
 * <p>Results are cached for a short TTL: tender capture is the POS hot path and the enabled-methods
 * set changes rarely. Lookup failures return {@code null} (unknown) so the caller can fail open —
 * blocking every sale in the shop because tenant-svc is briefly down would be worse than briefly
 * accepting a tender the owner disabled.
 */
@ApplicationScoped
public class TenantStoreClient {

  private static final Logger LOG = System.getLogger(TenantStoreClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";
  private static final long CACHE_TTL_MILLIS = 60_000;

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  private record CacheEntry(Set<String> methods, long fetchedAt) {}

  private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  /**
   * The enabled payment-method codes for a store, or {@code Optional.empty()} when they can't be
   * determined right now (tenant-svc unreachable / unexpected response) — callers treat an empty
   * Optional as "don't enforce".
   */
  public Optional<Set<String>> enabledMethods(UUID tenantId, UUID storeId) {
    String key = tenantId + ":" + storeId;
    CacheEntry cached = cache.get(key);
    long now = System.currentTimeMillis();
    if (cached != null && now - cached.fetchedAt() < CACHE_TTL_MILLIS) {
      return Optional.of(cached.methods());
    }
    Optional<Set<String>> fetched = fetch(tenantId, storeId);
    if (fetched.isPresent()) {
      cache.put(key, new CacheEntry(fetched.get(), now));
      return fetched;
    }
    // Serve stale over nothing: an expired entry still reflects the owner's last-known intent.
    return cached != null ? Optional.of(cached.methods()) : Optional.empty();
  }

  private Optional<Set<String>> fetch(UUID tenantId, UUID storeId) {
    try {
      ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
      if (instance == null) {
        LOG.log(Level.WARNING, "no healthy tenant-svc instance — skipping method enforcement");
        return Optional.empty();
      }
      try (HttpClientResponse res =
          webClient
              .get(instance.baseUri() + "/storefront/config")
              .queryParam("store", storeId.toString())
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .request()) {
        if (res.status().code() != 200) {
          LOG.log(
              Level.WARNING,
              "tenant-svc store config returned HTTP {0} — skipping method enforcement",
              res.status().code());
          return Optional.empty();
        }
        String body = res.as(String.class);
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
          JsonObject data = reader.readObject().getJsonObject("data");
          var arr = data.getJsonArray("enabledPaymentMethods");
          if (arr == null) return Optional.empty();
          List<String> methods = new ArrayList<>(arr.size());
          for (int i = 0; i < arr.size(); i++) {
            methods.add(arr.getString(i));
          }
          return Optional.of(Set.copyOf(methods));
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "tenant-svc store config lookup failed ({0}) — skipping method enforcement",
          e.getMessage());
      return Optional.empty();
    }
  }
}
