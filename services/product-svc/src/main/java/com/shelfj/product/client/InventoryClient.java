package com.shelfj.product.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.product.config.ServiceConfig;
import com.shelfj.web.ApiException;
import com.shelfj.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
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
 * Calls inventory-svc {@code POST /admin/inventory/receive/batch} to receive stock for all
 * catalogue-import rows in a single round-trip. Consul-resolved (golden rule #4).
 */
@ApplicationScoped
public class InventoryClient {

  private static final String INVENTORY_SERVICE = "inventory-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(30))
            .build();
  }

  public record ReceiveItem(String variantId, BigDecimal qty) {}

  public record BatchResult(int received, List<String> errors) {
    public BatchResult {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Sends a single batch receive request to inventory-svc. Partial failures are non-fatal: the
   * return value reports how many lines succeeded and which failed.
   *
   * <p>{@code @Retry}/{@code @CircuitBreaker}: same pattern as order-svc's inventory client — up to
   * 2 retries on transient errors, circuit trips after 60% failures in a 5-call window so a dead
   * inventory-svc fails fast on repeated import attempts instead of blocking each one for the full
   * read timeout.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public BatchResult batchReceive(
      UUID tenantId, UUID storeId, String rolesHeader, List<ReceiveItem> items) {
    if (items.isEmpty()) return new BatchResult(0, List.of());

    var instance =
        registry
            .resolve(INVENTORY_SERVICE)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "INVENTORY_UNAVAILABLE",
                        "no healthy inventory-svc instance in discovery",
                        List.of(),
                        null));

    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (var item : items) {
      arr.add(
          Json.createObjectBuilder()
              .add("storeId", storeId.toString())
              .add("variantId", item.variantId())
              .add("qty", item.qty()));
    }
    String body = Json.createObjectBuilder().add("items", arr).build().toString();

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/admin/inventory/receive/batch")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), rolesHeader)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(body)) {
      int status = res.status().code();
      String resp = res.as(String.class);
      if (status >= 500) {
        return new BatchResult(0, List.of("inventory-svc error HTTP " + status));
      }
      try (JsonReader reader = Json.createReader(new StringReader(resp))) {
        var data = reader.readObject().getJsonObject("data");
        int received = data.getInt("received", 0);
        var errs = new ArrayList<String>();
        var errArr = data.getJsonArray("errors");
        if (errArr != null) {
          for (int i = 0; i < errArr.size(); i++) {
            errs.add(errArr.getString(i));
          }
        }
        return new BatchResult(received, errs);
      }
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      return new BatchResult(0, List.of("inventory-svc circuit open — too many recent failures"));
    } catch (RuntimeException e) {
      return new BatchResult(0, List.of("inventory-svc unreachable: " + e.getMessage()));
    }
  }
}
