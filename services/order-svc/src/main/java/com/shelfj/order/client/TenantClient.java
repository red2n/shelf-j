package com.shelfj.order.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.web.ApiException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
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
}
