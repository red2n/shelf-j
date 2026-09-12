package com.shelfj.order.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.web.ApiException;
import com.shelfj.web.HttpHeaders;
import com.shelfj.web.TenantContext;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for tenant-svc fulfilment resolve (golden rule #1/#4). Used on DELIVERY orders to
 * pick the store that covers the delivery pincode.
 */
@ApplicationScoped
public class TenantClient {

  private static final Logger LOG = System.getLogger(TenantClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  public record ResolvedStore(UUID storeId, String storeName, String storeCode) {}

  /**
   * Resolves the fulfilling store for a delivery pincode. Empty when tenant-svc is unreachable and
   * the caller should keep the client-supplied store. Throws 404 when the pincode is not covered
   * (areas configured but no match).
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<ResolvedStore> resolveFulfilment(UUID tenantId, String pincode) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "tenant-svc not in discovery — keeping client storeId for DELIVERY");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/fulfilment/resolve")
            .queryParam("pincode", pincode)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status == 200) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
          JsonObject data = reader.readObject().getJsonObject("data");
          return Optional.of(
              new ResolvedStore(
                  UUID.fromString(data.getString("storeId")),
                  data.getString("storeName", ""),
                  data.getString("storeCode", "")));
        }
      }
      if (status == 404) {
        throw new ApiException(
            404,
            "FULFILMENT_AREA_NOT_COVERED",
            "No store delivers to pincode " + pincode,
            List.of());
      }
      LOG.log(Level.WARNING, "fulfilment resolve HTTP {0}: {1}", status, body);
      return Optional.empty();
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "tenant-svc circuit open — keeping client storeId for DELIVERY");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "fulfilment resolve failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /** The trading entity, as tenant-svc holds it: what a fiscal file's header names. */
  public record TenantIdentity(String legalName, String name, String country, String currency) {}

  /** A store's postal identity, as tenant-svc holds it: what a fiscal file's location names. */
  public record StoreIdentity(
      UUID id,
      String name,
      String code,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode) {}

  /**
   * The tenant's legal identity (18.5), read with the caller's own identity forwarded — a manager
   * exporting the register is a staff member, and {@code GET /admin/tenant} is a staff read.
   *
   * @return the identity, or empty when tenant-svc could not be reached or refused
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "tenantUnavailable")
  public Optional<TenantIdentity> tenant(UUID tenantId, TenantContext ctx) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        forward(webClient.get(instance.baseUri() + "/admin/tenant"), tenantId, ctx).request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "tenant read HTTP {0}: {1}", res.status().code(), body);
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject d = reader.readObject().getJsonObject("data");
        return Optional.of(
            new TenantIdentity(
                d.getString("legalName", null),
                d.getString("name", null),
                d.getString("country", null),
                d.getString("currency", null)));
      }
    }
  }

  /**
   * A store's postal identity (18.5), read with the caller's identity forwarded.
   *
   * @return the store, or empty when tenant-svc could not be reached, or the store is not this
   *     tenant's
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "storeUnavailable")
  public Optional<StoreIdentity> store(UUID tenantId, UUID storeId, TenantContext ctx) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    try (HttpClientResponse res =
        forward(webClient.get(instance.baseUri() + "/admin/stores/" + storeId), tenantId, ctx)
            .request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(Level.WARNING, "store read HTTP {0}: {1}", res.status().code(), body);
        return Optional.empty();
      }
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject d = reader.readObject().getJsonObject("data");
        return Optional.of(
            new StoreIdentity(
                storeId,
                d.getString("name", null),
                d.getString("code", null),
                d.getString("line1", null),
                d.getString("line2", null),
                d.getString("city", null),
                d.getString("state", null),
                d.getString("country", null),
                d.getString("pincode", null)));
      }
    }
  }

  private static io.helidon.webclient.api.HttpClientRequest forward(
      io.helidon.webclient.api.HttpClientRequest req, UUID tenantId, TenantContext ctx) {
    io.helidon.webclient.api.HttpClientRequest out =
        req.header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), String.join(",", ctx.roles()));
    if (ctx.userId() != null) {
      out = out.header(HeaderNames.create(HttpHeaders.USER_ID), ctx.userId().toString());
    }
    return out;
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<TenantIdentity> tenantUnavailable(UUID tenantId, TenantContext ctx) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; tenant identity not read");
    return Optional.empty();
  }

  @SuppressWarnings("unused")
  Optional<StoreIdentity> storeUnavailable(UUID tenantId, UUID storeId, TenantContext ctx) {
    LOG.log(Level.WARNING, "tenant-svc unavailable; store identity not read");
    return Optional.empty();
  }
}
