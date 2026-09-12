package com.shelfj.order.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.order.config.ServiceConfig;
import com.shelfj.web.HttpHeaders;
import com.shelfj.web.TenantContext;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Sync client for product-svc's variant lookup (golden rule #1 — the names live there; rule #4 —
 * the instance comes from Consul). A fiscal file lists every line by a product's name and code, and
 * this service holds only the variant id.
 *
 * <p>The caller's identity is forwarded, not this service's: the lookup is a staff read in
 * product-svc, and the manager exporting the register is a staff member.
 */
@ApplicationScoped
public class ProductClient {

  private static final System.Logger LOG = System.getLogger(ProductClient.class.getName());
  private static final String PRODUCT_SERVICE = "product-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(8))
            .build();
  }

  /** What a line prints: the product's name, its SKU and its unit. */
  public record VariantName(String productName, String sku, String unit) {}

  /**
   * The printable names of a set of variants.
   *
   * @param tenantId the tenant, as the caller's context carries it
   * @param ids the variants to name
   * @param ctx the caller, whose identity is forwarded
   * @return names by variant id; empty when product-svc could not be reached
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "unavailable")
  public Optional<Map<UUID, VariantName>> names(
      UUID tenantId, Collection<UUID> ids, TenantContext ctx) {
    if (ids.isEmpty()) {
      return Optional.of(Map.of());
    }
    ServiceInstance instance = registry.resolve(PRODUCT_SERVICE).orElse(null);
    if (instance == null) {
      return Optional.empty();
    }
    String joined = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    var req =
        webClient
            .get(instance.baseUri() + "/admin/products/variants/resolve")
            .queryParam("ids", joined)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), String.join(",", ctx.roles()));
    if (ctx.userId() != null) {
      req = req.header(HeaderNames.create(HttpHeaders.USER_ID), ctx.userId().toString());
    }
    try (HttpClientResponse res = req.request()) {
      String body = res.as(String.class);
      if (res.status().code() != 200) {
        LOG.log(
            System.Logger.Level.WARNING,
            "variant resolve HTTP {0}: {1}",
            res.status().code(),
            body);
        return Optional.empty();
      }
      Map<UUID, VariantName> out = new HashMap<>();
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (var v : data.getValuesAs(JsonObject.class)) {
            if (v.containsKey("variantId") && !v.isNull("variantId")) {
              out.put(
                  UUID.fromString(v.getString("variantId")),
                  new VariantName(
                      v.getString("productName", null),
                      v.getString("sku", null),
                      v.getString("unit", null)));
            }
          }
        }
      }
      return Optional.of(out);
    }
  }

  // Only called reflectively by MicroProfile Fault Tolerance via @Fallback above.
  @SuppressWarnings("unused")
  Optional<Map<UUID, VariantName>> unavailable(
      UUID tenantId, Collection<UUID> ids, TenantContext ctx) {
    LOG.log(System.Logger.Level.WARNING, "product-svc unavailable; variant names not resolved");
    return Optional.empty();
  }
}
