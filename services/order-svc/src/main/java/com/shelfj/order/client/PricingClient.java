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
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Sync client for pricing-svc's {@code POST /prices/resolve} (golden rule #1: foreign data comes
 * from the owning service, never its tables; rule #4: the instance is resolved via Consul, not a
 * hardcoded host:port).
 *
 * <p>Fail-closed: when price enforcement is on and pricing-svc is unreachable or has no price for
 * the variant, order placement is rejected — a sale at a client-chosen price is worse than a lost
 * sale.
 */
@ApplicationScoped
public class PricingClient {

  private static final String PRICING_SERVICE = "pricing-svc";

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

  /**
   * Returns the effective unit price for one order line, as decided by pricing-svc (price list +
   * active promotions). Throws 422 when no price is configured, 503 when pricing-svc cannot be
   * reached.
   */
  public BigDecimal resolveUnitPrice(
      UUID tenantId, UUID variantId, UUID storeId, String channel, BigDecimal qty) {
    ServiceInstance instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonObjectBuilder payload = Json.createObjectBuilder().add("variantId", variantId.toString());
    if (storeId != null) payload.add("storeId", storeId.toString());
    if (channel != null) payload.add("channel", channel);
    if (qty != null) payload.add("qty", qty);

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/prices/resolve")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload.build().toString())) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for variant " + variantId);
      }
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        return data.getJsonNumber("unitPrice").bigDecimalValue();
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "ORDER_PRICING_UNAVAILABLE", message, List.of(), cause);
  }
}
