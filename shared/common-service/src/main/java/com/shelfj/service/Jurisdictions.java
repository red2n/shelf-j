package com.shelfj.service;

import com.shelfj.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Which laws bind the tenant a service is acting for, read from tenant-svc's jurisdiction rules
 * ({@code GET /admin/tenant/obligations}) instead of a list of countries and dates in the service's
 * own code — the lesson of SJ-D53, applied to the law.
 *
 * <p><b>Refuses rather than guesses.</b> When the rules cannot be read, {@link #inForce} throws
 * {@code 503 OBLIGATIONS_UNAVAILABLE}. A duty assumed absent is how a till takes a payment the law
 * forbids; a duty assumed present blocks a sale the law allows. The caller decides which way a
 * refused answer should fail for its own case.
 *
 * <p>A country's rules change only with the law, by migration, so they are cached per country for
 * {@link #TTL}; a failed read is not cached. Every window is fetched, not only today's, so a
 * question about any day is answered from the same list.
 */
@ApplicationScoped
public class Jurisdictions {

  private static final Logger LOG = System.getLogger(Jurisdictions.class.getName());
  static final Duration TTL = Duration.ofHours(1);

  /**
   * The earliest day tenant-svc accepts; asking "as of" it returns every window, since nothing had
   * ended by then.
   */
  private static final String EVERY_WINDOW = "1900-01-01";

  /** One obligation as it reaches a country, and the window it applies in. */
  public record Obligation(
      String code, String scope, LocalDate effectiveFrom, LocalDate effectiveTo) {

    /** True from its first day to its last, inclusive; an open window never ends. */
    public boolean inForceOn(LocalDate day) {
      return !effectiveFrom.isAfter(day) && (effectiveTo == null || !effectiveTo.isBefore(day));
    }
  }

  private record Cached(List<Obligation> obligations, Instant expiresAt) {}

  @Inject TenantProfiles profiles;
  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "shelfj.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private BiFunction<UUID, String, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch =
        (tenantId, country) ->
            client.body(
                tenantId,
                "/admin/tenant/obligations",
                Map.of("country", country, "on", EVERY_WINDOW));
  }

  /** For tests: the tenant profiles to take a country from, a stand-in fetch, and a clock. */
  static Jurisdictions forTest(
      TenantProfiles profiles, BiFunction<UUID, String, Optional<String>> fetch, Clock clock) {
    Jurisdictions j = new Jurisdictions();
    j.profiles = profiles;
    j.fetch = fetch;
    j.clock = clock;
    return j;
  }

  /**
   * Whether an obligation binds the tenant's own country on a day.
   *
   * @param code the obligation's code, such as {@code PRICE_REDUCTION_PRIOR_PRICE}
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} or {@code OBLIGATIONS_UNAVAILABLE}
   */
  public boolean inForce(UUID tenantId, String code, LocalDate day) {
    return inForceIn(tenantId, profiles.requireCountry(tenantId), code, day);
  }

  /**
   * Whether an obligation binds a named country on a day — for a store across a border from the
   * business that owns it.
   *
   * @throws ApiException 503 {@code OBLIGATIONS_UNAVAILABLE}
   */
  public boolean inForceIn(UUID tenantId, String country, String code, LocalDate day) {
    return obligations(tenantId, country).stream()
        .anyMatch(o -> o.code().equals(code) && o.inForceOn(day));
  }

  /**
   * The countries whose law reaches an offer: the business's own and, at one of its stores, that
   * store's. With no store — or a store that is not the business's — every store's, since an offer
   * made nowhere in particular is made wherever the business trades. A store that records no
   * country trades in the business's own.
   *
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} or {@code
   *     TENANT_STORES_UNAVAILABLE}
   */
  public Set<String> countriesTrading(UUID tenantId, UUID storeId) {
    Set<String> out = new TreeSet<>();
    out.add(profiles.requireCountry(tenantId));
    TenantProfiles.Stores stores = profiles.stores(tenantId, storeId);
    if (stores.has(storeId)) {
      String country = stores.countries().get(storeId);
      if (country != null) out.add(country);
    } else {
      out.addAll(stores.countries().values());
    }
    return Set.copyOf(out);
  }

  /**
   * Whether an obligation binds any country an offer reaches, by {@link #countriesTrading}: a store
   * across a border cannot trade outside the law of the country it stands in.
   *
   * @throws ApiException 503 when the tenant, its stores or any country's rules cannot be read
   */
  public boolean inForceWhereTrading(UUID tenantId, UUID storeId, String code, LocalDate day) {
    for (String country : countriesTrading(tenantId, storeId)) {
      if (inForceIn(tenantId, country, code, day)) return true;
    }
    return false;
  }

  /**
   * Every obligation that reaches a country, with its window.
   *
   * @throws ApiException 503 {@code OBLIGATIONS_UNAVAILABLE} when tenant-svc cannot answer
   */
  public List<Obligation> obligations(UUID tenantId, String country) {
    String cc = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
    Instant now = clock.instant();
    Cached hit = cache.get(cc);
    if (hit != null && hit.expiresAt().isAfter(now)) return hit.obligations();
    List<Obligation> read =
        fetch
            .apply(tenantId, cc)
            .flatMap(Jurisdictions::parse)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "OBLIGATIONS_UNAVAILABLE",
                        "the legal obligations for "
                            + cc
                            + " could not be read from tenant-svc; nothing was assumed, try again",
                        List.of()));
    cache.put(cc, new Cached(read, now.plus(TTL)));
    return read;
  }

  /**
   * Reads a {@code GET /admin/tenant/obligations} response; empty when any obligation in it cannot
   * be read, because a list with one duty silently missing is worse than no list.
   */
  static Optional<List<Obligation>> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject data = root.getJsonObject("data");
      if (!data.containsKey("obligations") || data.isNull("obligations")) return Optional.empty();
      List<Obligation> out = new ArrayList<>();
      for (JsonValue value : data.getJsonArray("obligations")) {
        JsonObject o = value.asJsonObject();
        LocalDate to =
            o.containsKey("effectiveTo") && !o.isNull("effectiveTo")
                ? LocalDate.parse(o.getString("effectiveTo"))
                : null;
        out.add(
            new Obligation(
                o.getString("code"),
                o.getString("scope", ""),
                LocalDate.parse(o.getString("effectiveFrom")),
                to));
      }
      return Optional.of(List.copyOf(out));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "unreadable legal obligations: {0}", e.getMessage());
      return Optional.empty();
    }
  }
}
