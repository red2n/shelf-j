package com.shelfj.purchase.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.purchase.config.ServiceConfig;
import com.shelfj.web.HttpHeaders;
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
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Asks inventory-svc, which owns stock (golden rule #1), what a store has on hand of a variant — so
 * a return to vendor of goods that were already sold is refused where the buyer can see it rather
 * than skipped line by line in a consumer.
 *
 * <p>Advisory, not authoritative: an empty answer means inventory-svc could not say, and the return
 * goes ahead; the stock movement itself happens where the stock lives, when {@code
 * ReturnedToVendor} is consumed. The same shape as {@link PricingClient}.
 */
@ApplicationScoped
public class InventoryClient {

  private static final Logger LOG = System.getLogger(InventoryClient.class.getName());
  private static final String INVENTORY_SERVICE = "inventory-svc";

  /** {@code GET /admin/inventory/batches} is a staff-operable admin read. */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

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
   * What is on hand and available of a variant at a store: the remaining quantity of every
   * available batch, summed.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param variantId the variant
   * @return the quantity, or empty when inventory-svc could not be reached or refused
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<BigDecimal> onHand(UUID tenantId, UUID storeId, UUID variantId) {
    ServiceInstance instance = registry.resolve(INVENTORY_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "inventory-svc not in discovery — on-hand not checked");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/admin/inventory/batches")
            .queryParam("store", storeId.toString())
            .queryParam("variant", variantId.toString())
            .queryParam("material_status", "AVAILABLE")
            .queryParam("limit", "100")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "on-hand lookup HTTP {0}: {1}", status, body);
        return Optional.empty();
      }
      BigDecimal total = BigDecimal.ZERO;
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (JsonObject b : data.getValuesAs(JsonObject.class)) {
            if (b.containsKey("remainingQty") && !b.isNull("remainingQty")) {
              total = total.add(new BigDecimal(b.get("remainingQty").toString()));
            }
          }
        }
      }
      return Optional.of(total);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "inventory-svc circuit open — on-hand not checked");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "on-hand lookup failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
