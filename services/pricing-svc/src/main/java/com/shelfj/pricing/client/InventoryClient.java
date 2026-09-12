package com.shelfj.pricing.client;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
import com.shelfj.discovery.ServiceRegistry;
import com.shelfj.pricing.config.ServiceConfig;
import com.shelfj.web.HttpHeaders;
import com.shelfj.web.TenantContext;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Reads the batches a store has coming up to their date from inventory-svc, which owns them (golden
 * rule #1; the instance comes from Consul, rule #4). The morning's markdown plan is those batches
 * beside this service's prices and the ladder. The caller's identity is forwarded: the expiring
 * view is a staff read there, and the person planning the counter is staff.
 */
@ApplicationScoped
public class InventoryClient {

  private static final Logger LOG = System.getLogger(InventoryClient.class.getName());
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
            .readTimeout(Duration.ofSeconds(8))
            .build();
  }

  /** A batch coming up to its date, as inventory-svc lists it. */
  public record ExpiringBatch(
      UUID batchId,
      UUID variantId,
      String batchNo,
      BigDecimal remainingQty,
      LocalDate expiryDate,
      long daysUntilExpiry) {}

  /**
   * The store's batches expiring within a number of days.
   *
   * @param tenantId the tenant, as the caller's context carries it
   * @param storeId the store
   * @param withinDays the horizon
   * @param ctx the caller, whose identity is forwarded
   * @return the batches, or empty when inventory-svc could not be reached or refused
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<List<ExpiringBatch>> expiringBatches(
      UUID tenantId, UUID storeId, int withinDays, TenantContext ctx) {
    ServiceInstance instance = registry.resolve(INVENTORY_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "inventory-svc not in discovery — expiring batches not read");
      return Optional.empty();
    }
    HttpClientRequest req =
        webClient
            .get(instance.baseUri() + "/admin/inventory/batches/expiring")
            .queryParam("store", storeId.toString())
            .queryParam("withinDays", Integer.toString(withinDays))
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), String.join(",", ctx.roles()));
    if (ctx.userId() != null) {
      req = req.header(HeaderNames.create(HttpHeaders.USER_ID), ctx.userId().toString());
    }
    try (HttpClientResponse res = req.request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "expiring batches HTTP {0}: {1}", status, body);
        return Optional.empty();
      }
      List<ExpiringBatch> out = new ArrayList<>();
      try (JsonReader reader = Json.createReader(new StringReader(body))) {
        JsonArray data = reader.readObject().getJsonArray("data");
        if (data != null) {
          for (JsonObject b : data.getValuesAs(JsonObject.class)) {
            out.add(
                new ExpiringBatch(
                    UUID.fromString(b.getString("id")),
                    UUID.fromString(b.getString("variantId")),
                    b.getString("batchNo", null),
                    new BigDecimal(b.get("remainingQty").toString()),
                    b.containsKey("expiryDate") && !b.isNull("expiryDate")
                        ? LocalDate.parse(b.getString("expiryDate"))
                        : null,
                    b.containsKey("daysUntilExpiry")
                        ? b.getJsonNumber("daysUntilExpiry").longValue()
                        : 0L));
          }
        }
      }
      return Optional.of(out);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "inventory-svc circuit open — expiring batches not read");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "expiring batches read failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
