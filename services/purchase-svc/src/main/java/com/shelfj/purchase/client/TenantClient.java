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
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Reads the tenant's declared trading currency from tenant-svc, which owns it (golden rule #1:
 * foreign data comes from the owning service, never its tables; rule #4: the instance is resolved
 * through Consul).
 *
 * <p><b>Why a synchronous call and not a projection.</b> order-svc projects the same fact onto a
 * local {@code tenant_status} row, and its migration says why in as many words: it refused to put a
 * network hop on the checkout hot path. Raising a purchase order is not that path — it is a
 * back-office action a buyer performs a handful of times a day — so the projection's cost (a Kafka
 * consumer, a migration, two event handlers and a repair endpoint for tenants onboarded before the
 * consumer existed) buys nothing here, and a synchronous read cannot go stale.
 *
 * <p><b>Fail-open, deliberately.</b> An unreachable tenant-svc yields empty and the caller falls
 * back to the configured platform default, exactly as {@code OrderService.resolveCurrency} does for
 * a tenant with no projection yet. The alternative — refusing to create a purchase order because
 * tenant-svc is down — trades a rare wrong-looking default for a common outright outage, and the
 * currency is still overridable by the caller.
 */
@ApplicationScoped
public class TenantClient {

  private static final Logger LOG = System.getLogger(TenantClient.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";
  private static final int ISO_4217_LENGTH = 3;

  /**
   * {@code GET /admin/tenant} sits in the filter's staff-operable tier, so the lowest staff role
   * satisfies it. Stamped explicitly rather than sent with no principal, for the reason SJ-D13
   * established: a call carrying no identity is one routing mistake away from being an
   * impersonation, and the bypass that used to serve those calls has been removed.
   */
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
   * The tenant's declared ISO 4217 currency.
   *
   * <p>{@code @Retry}: two retries on a transient network error. {@code @CircuitBreaker}: trips at
   * 60% failures over a 5-request window and stays open 5s, so a dead tenant-svc does not make
   * every purchase order wait for a timeout before falling back.
   *
   * @param tenantId the tenant to look up
   * @return the currency, or empty when tenant-svc is unreachable, has no such tenant, or returns a
   *     currency that is not a 3-character code
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public Optional<String> findCurrency(UUID tenantId) {
    ServiceInstance instance = registry.resolve(TENANT_SERVICE).orElse(null);
    if (instance == null) {
      LOG.log(Level.WARNING, "tenant-svc not in discovery — falling back to the default currency");
      return Optional.empty();
    }
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/admin/tenant")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      int status = res.status().code();
      String body = res.as(String.class);
      if (status != 200) {
        LOG.log(Level.WARNING, "tenant currency lookup HTTP {0}: {1}", status, body);
        return Optional.empty();
      }
      return parseCurrency(body);
    } catch (CircuitBreakerOpenException e) {
      LOG.log(Level.WARNING, "tenant-svc circuit open — falling back to the default currency");
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "tenant currency lookup failed: {0}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Extracted from the call above so it can be asserted at all, for the reason SJ-D14 and SJ-D20
   * both established: the two places in this codebase that parsed a cross-service response inline —
   * behind service discovery and a circuit breaker — were the two that were wrong and stayed wrong
   * for months, because no test could reach them.
   *
   * <p>Absent, null and blank all mean the same thing here and are treated the same: JSON-B omits a
   * null field rather than serialising it, so {@code isNull} on a missing key throws (SJ-D14).
   *
   * @param body the raw {@code GET /admin/tenant} response
   * @return the upper-cased currency, or empty if the payload does not carry a usable one
   */
  static Optional<String> parseCurrency(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject data = reader.readObject().getJsonObject("data");
      if (data == null) return Optional.empty();
      String currency = data.getString("currency", null);
      if (currency == null || currency.trim().length() != ISO_4217_LENGTH) return Optional.empty();
      return Optional.of(currency.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "malformed tenant payload: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
