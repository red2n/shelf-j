package com.shelfj.payment.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.payment.config.ServiceConfig;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for order-svc's {@code GET /orders/{id}}, used to verify an online payment claim
 * against the order it's captured against (golden rule #1: foreign data comes from the owning
 * service, never trusted from the caller; rule #4: the instance is resolved via Consul).
 */
@ApplicationScoped
public class OrderClient {

  private static final String ORDER_SERVICE = "order-svc";

  /** See the header comment in {@link #getOrder}. */
  private static final String INTERNAL_ROLE = "CASHIER";

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

  /** The order-svc fields needed to validate a payment claim against the order it targets. */
  public record OrderInfo(
      String customerId, String channel, BigDecimal total, String status, String storeId) {}

  /**
   * Throws 404 when the order doesn't exist in the tenant, 503 when order-svc cannot be reached.
   *
   * <p>{@code @Retry}/{@code @CircuitBreaker}: same rationale as {@code PricingClient} — retry
   * transient failures, trip after sustained failures so a dead order-svc fails fast instead of
   * blocking every online payment for 5s.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public OrderInfo getOrder(UUID tenantId, UUID orderId) {
    ServiceInstance instance =
        registry
            .resolve(ORDER_SERVICE)
            .orElseThrow(() -> unavailable("no healthy order-svc instance in discovery", null));

    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/orders/" + orderId)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            // Trusted service-to-service call behind the gateway. It stamps a staff role for the
            // same reason CustomerClient does: reading an order is now staff-gated, and taking
            // payment for one is a till action. Without this the call reads as an anonymous
            // customer and order-svc's object-level check refuses it.
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      if (status == 404) {
        throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
      }
      if (status != 200) {
        throw unavailable("order-svc returned HTTP " + status, null);
      }
      String body = res.as(String.class);
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        return new OrderInfo(
            data.isNull("customerId") ? null : data.getString("customerId"),
            data.getString("channel"),
            data.getJsonNumber("total").bigDecimalValue(),
            data.getString("status"),
            data.getString("storeId"));
      } catch (RuntimeException e) {
        throw unavailable("malformed response from order-svc", e);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("order-svc circuit open — too many recent failures", e);
    } catch (RuntimeException e) {
      throw unavailable("order-svc unreachable", e);
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(503, "PAYMENT_ORDER_LOOKUP_UNAVAILABLE", message, List.of(), cause);
  }
}
