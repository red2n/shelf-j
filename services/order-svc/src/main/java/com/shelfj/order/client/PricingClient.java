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
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

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
   * The pricing-svc-resolved figures for one order line: {@code unitPrice} already has any active
   * promotion discount applied, and {@code vatAmount} is the per-unit tax pricing-svc computed from
   * the variant's VAT category. Both are authoritative — never overridden by client input when
   * price enforcement is on.
   */
  public record ResolvedLine(BigDecimal unitPrice, BigDecimal vatAmount) {}

  /**
   * Returns the effective unit price and VAT for one order line, as decided by pricing-svc (price
   * list + active promotions + VAT rate). Throws 422 when no price is configured, 503 when
   * pricing-svc cannot be reached.
   *
   * <p>{@code @Retry}: up to 2 retries on transient network errors; aborts immediately on {@link
   * ApiException} (a valid error response from pricing-svc — retrying a 404 is pointless).
   * {@code @CircuitBreaker}: trips after 60 % failures in a 5-request window; stays open for 5 s so
   * a dead pricing-svc doesn't cause every checkout to block for 5 s before failing. {@link
   * CircuitBreakerOpenException} is caught below and mapped to 503.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public ResolvedLine resolveLine(
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
        BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
        BigDecimal vatAmount =
            data.containsKey("vatAmount") && !data.isNull("vatAmount")
                ? data.getJsonNumber("vatAmount").bigDecimalValue()
                : BigDecimal.ZERO;
        return new ResolvedLine(unitPrice, vatAmount);
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  /** One line to resolve in a {@link #resolveLines} batch call. */
  public record LineRequest(UUID variantId, BigDecimal qty) {}

  /**
   * Batch form of {@link #resolveLine} — resolves every line of an order in one HTTP call instead
   * of one call per line, removing the per-line round trip (and circuit-breaker/retry overhead)
   * that checkout used to pay once per item. Results are returned in the same order as {@code
   * lines}. Same fail-closed behavior as the single-line form: any line that can't be priced fails
   * the whole call (placeOrder never persisted a partially-priced order before this change either).
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public List<ResolvedLine> resolveLines(
      UUID tenantId, List<LineRequest> lines, UUID storeId, String channel) {
    if (lines.isEmpty()) return List.of();
    ServiceInstance instance =
        registry
            .resolve(PRICING_SERVICE)
            .orElseThrow(() -> unavailable("no healthy pricing-svc instance in discovery", null));

    JsonArrayBuilder linesArray = Json.createArrayBuilder();
    for (LineRequest l : lines) {
      JsonObjectBuilder lineObj =
          Json.createObjectBuilder().add("variantId", l.variantId().toString());
      if (storeId != null) lineObj.add("storeId", storeId.toString());
      if (channel != null) lineObj.add("channel", channel);
      if (l.qty() != null) lineObj.add("qty", l.qty());
      linesArray.add(lineObj);
    }
    String payload = Json.createObjectBuilder().add("lines", linesArray).build().toString();

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/prices/resolve-batch")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.unprocessable(
            "ORDER_PRICE_UNRESOLVED", "no active price configured for one or more order lines");
      }
      if (status != 200) {
        throw unavailable("pricing-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray results = reader.readObject().getJsonObject("data").getJsonArray("results");
        List<ResolvedLine> resolved = new ArrayList<>(results.size());
        for (var r : results) {
          JsonObject data = r.asJsonObject();
          BigDecimal unitPrice = data.getJsonNumber("unitPrice").bigDecimalValue();
          BigDecimal vatAmount =
              data.containsKey("vatAmount") && !data.isNull("vatAmount")
                  ? data.getJsonNumber("vatAmount").bigDecimalValue()
                  : BigDecimal.ZERO;
          resolved.add(new ResolvedLine(unitPrice, vatAmount));
        }
        return resolved;
      } catch (RuntimeException e) {
        throw unavailable("malformed response from pricing-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("pricing-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("pricing-svc unreachable", e);
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "ORDER_PRICING_UNAVAILABLE", message, List.of(), cause);
  }
}
