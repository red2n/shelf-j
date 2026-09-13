package com.shelfj.service;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.discovery.ServiceInstance;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A tenant's own trading currency and country, read from tenant-svc, which owns them (golden rule
 * #1), for every service that has to stamp one onto a row or a response.
 *
 * <p><b>Why this exists.</b> Services used to fill a missing currency with a literal — {@code
 * "GBP"} in pricing, customer, payment, product and order code, {@code "EUR"} in fiscal code — and
 * a missing country with {@code "GB"}. On a multi-tenant platform that is not a harmless default: a
 * Japanese tenant's price list, store credit or Z-report came out in pounds with no error. The
 * tenant declares both at onboarding and neither can change afterwards, so the right answer is
 * always available; this reads it.
 *
 * <p><b>No fallback, deliberately.</b> When the profile cannot be read, {@link #requireCurrency}
 * and {@link #requireCountry} refuse with {@code 503 TENANT_PROFILE_UNAVAILABLE} rather than guess.
 * A refused write can be retried; money recorded in the wrong currency cannot be told apart from
 * money recorded in the right one. Callers that only display a value use {@link #find}.
 *
 * <p>Profiles are cached per tenant for {@link #TTL}: the two fields are fixed at onboarding, so a
 * short cache costs nothing in correctness and keeps a busy till from asking tenant-svc on every
 * sale. A failed read is not cached, so an outage ends when tenant-svc answers again.
 *
 * <p>The instance is resolved through Consul (rule #4), or taken from {@code
 * shelfj.clients.tenant-svc.url} when that is set — for a deployment without discovery and for
 * integration tests, which point it at a stub.
 */
@ApplicationScoped
public class TenantProfiles {

  private static final Logger LOG = System.getLogger(TenantProfiles.class.getName());
  private static final String TENANT_SERVICE = "tenant-svc";

  /**
   * {@code GET /admin/tenant} sits in the staff-operable tier, so the lowest staff role reaches it;
   * stamped explicitly, for the reason SJ-D13 established — a call carrying no identity is one
   * routing mistake away from an impersonation.
   */
  private static final String INTERNAL_ROLE = "STOREKEEPER";

  static final Duration TTL = Duration.ofMinutes(5);
  private static final int ATTEMPTS = 3;
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

  /** What a service needs to know about the tenant it is acting for. */
  public record Profile(UUID tenantId, String currency, String country) {}

  private record Cached(Profile profile, Instant expiresAt) {}

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "shelfj.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private Function<UUID, Optional<String>> fetch;

  @PostConstruct
  void init() {
    WebClient web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    ConsulClient consul =
        settings.consulEnabled()
            ? new ConsulClient(settings.consulHost(), settings.consulPort())
            : null;
    fetch = tenantId -> fetchOverHttp(web, consul, tenantId);
  }

  /** For tests: a fetch function standing in for tenant-svc, and a clock to age the cache with. */
  static TenantProfiles forTest(Function<UUID, Optional<String>> fetch, Clock clock) {
    TenantProfiles p = new TenantProfiles();
    p.fetch = fetch;
    p.clock = clock;
    return p;
  }

  /**
   * The tenant's profile, from the cache or tenant-svc.
   *
   * @return empty when tenant-svc cannot be reached, has no such tenant, or answers with a profile
   *     that does not carry a valid ISO 4217 currency and ISO 3166 country
   */
  public Optional<Profile> find(UUID tenantId) {
    if (tenantId == null) return Optional.empty();
    Instant now = clock.instant();
    Cached hit = cache.get(tenantId);
    if (hit != null && hit.expiresAt().isAfter(now)) return Optional.of(hit.profile());
    Optional<Profile> read = fetch.apply(tenantId).flatMap(body -> parse(tenantId, body));
    read.ifPresent(p -> cache.put(tenantId, new Cached(p, now.plus(TTL))));
    return read;
  }

  /**
   * The tenant's declared currency.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} when it cannot be read
   */
  public String requireCurrency(UUID tenantId) {
    return require(tenantId).currency();
  }

  /**
   * The tenant's declared country.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} when it cannot be read
   */
  public String requireCountry(UUID tenantId) {
    return require(tenantId).country();
  }

  /**
   * The currency a request names, or the tenant's own when it names none (SJ-D53).
   *
   * @param requested as sent; blank means none
   * @return an upper-cased ISO 4217 code
   * @throws ApiException 400 {@code CURRENCY_INVALID} for anything that is not one; 503 {@code
   *     TENANT_PROFILE_UNAVAILABLE} when none is named and the tenant's cannot be read
   */
  public String currencyOr(UUID tenantId, String requested) {
    String code = upper(requested);
    if (code == null || code.isEmpty()) return requireCurrency(tenantId);
    if (!ISO_CURRENCIES.contains(code)) {
      throw ApiException.badRequest(
          "CURRENCY_INVALID", "currency must be an ISO 4217 code such as EUR or JPY");
    }
    return code;
  }

  /**
   * The country a request names, or the tenant's own when it names none (SJ-D53).
   *
   * @param requested as sent; blank means none
   * @return an upper-cased ISO 3166-1 alpha-2 code
   * @throws ApiException 400 {@code COUNTRY_INVALID} for anything that is not one; 503 {@code
   *     TENANT_PROFILE_UNAVAILABLE} when none is named and the tenant's cannot be read
   */
  public String countryOr(UUID tenantId, String requested) {
    String code = upper(requested);
    if (code == null || code.isEmpty()) return requireCountry(tenantId);
    if (!ISO_COUNTRIES.contains(code)) {
      throw ApiException.badRequest(
          "COUNTRY_INVALID", "country must be an ISO 3166-1 alpha-2 code such as DE or JP");
    }
    return code;
  }

  private static final java.util.Set<String> ISO_CURRENCIES =
      java.util.Currency.getAvailableCurrencies().stream()
          .map(java.util.Currency::getCurrencyCode)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private static final java.util.Set<String> ISO_COUNTRIES =
      java.util.Set.of(Locale.getISOCountries());

  private Profile require(UUID tenantId) {
    return find(tenantId)
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "TENANT_PROFILE_UNAVAILABLE",
                    "the tenant's currency and country could not be read from tenant-svc;"
                        + " nothing was assumed, try again",
                    List.of()));
  }

  /**
   * Reads a {@code GET /admin/tenant} response. Extracted so every rule is reachable by a unit
   * test, for the reason SJ-D14 established: parsing inline behind discovery is where a
   * cross-service contract goes wrong and stays wrong.
   */
  static Optional<Profile> parse(UUID tenantId, String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      String currency = upper(data.getString("currency", null));
      String country = upper(data.getString("country", null));
      if (currency == null || !CURRENCY.matcher(currency).matches()) return Optional.empty();
      if (country == null || !COUNTRY.matcher(country).matches()) return Optional.empty();
      return Optional.of(new Profile(tenantId, currency, country));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "malformed tenant profile for {0}: {1}", tenantId, e.getMessage());
      return Optional.empty();
    }
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }

  private Optional<String> fetchOverHttp(WebClient web, ConsulClient consul, UUID tenantId) {
    String base = tenantSvcUrl.filter(u -> !u.isBlank()).orElse(null);
    if (base == null && consul != null) {
      base = consul.resolve(TENANT_SERVICE).map(ServiceInstance::baseUri).orElse(null);
    }
    if (base == null) {
      LOG.log(Level.WARNING, "tenant-svc could not be located for tenant {0}", tenantId);
      return Optional.empty();
    }
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try (HttpClientResponse res =
          web.get(base + "/admin/tenant")
              .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
              .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
              .request()) {
        int status = res.status().code();
        if (status == 200) return Optional.of(res.as(String.class));
        LOG.log(Level.WARNING, "tenant profile for {0}: HTTP {1}", tenantId, status);
        if (status < 500) return Optional.empty();
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "tenant profile for {0} failed: {1}", tenantId, e.getMessage());
      }
    }
    return Optional.empty();
  }
}
