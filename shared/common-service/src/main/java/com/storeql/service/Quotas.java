package com.storeql.service;

import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether a business may do more of a metered thing now (21.10), asked of tenant-svc — which counts
 * it — by the service about to do it.
 *
 * <p>Only a hard ceiling ever answers no, and only on a meter that may be refused: an order never
 * is, a marketing text may be. So this is asked before the refusable thing and nowhere else.
 *
 * <p><b>Fail open</b>, as {@link Entitlements} does and for the same reason: a quota is a
 * commercial boundary, not a safety one, and a blip in tenant-svc must not stop a shop sending.
 * Unlike a limit it is <b>not cached</b> — usage moves with every text, and a minute's stale answer
 * would let a whole campaign past a ceiling it had already reached.
 */
@ApplicationScoped
public class Quotas {

  private static final String PATH = "/admin/tenant/usage/allowance";

  /**
   * What tenant-svc answered.
   *
   * @param included null when there is no ceiling
   */
  public record Answer(boolean allowed, long used, Long included) {}

  @Inject ServiceSettings settings;

  @Inject
  @ConfigProperty(name = "storeql.clients.tenant-svc.url")
  Optional<String> tenantSvcUrl;

  private BiFunction<UUID, Map<String, String>, Optional<String>> fetch;

  @PostConstruct
  void init() {
    ServiceReader client = ServiceReader.tenantSvc(settings, tenantSvcUrl);
    fetch = (tenantId, query) -> client.body(tenantId, PATH, query);
  }

  /** For tests: an answer standing in for tenant-svc. */
  static Quotas forTest(BiFunction<UUID, Map<String, String>, Optional<String>> fetch) {
    Quotas q = new Quotas();
    q.fetch = fetch;
    return q;
  }

  /**
   * What tenant-svc says of {@code quantity} more of a meter.
   *
   * @return empty when it could not be asked or did not answer sensibly — which the caller treats
   *     as allowed
   */
  public Optional<Answer> ask(UUID tenantId, String meter, long quantity) {
    if (tenantId == null) return Optional.empty();
    return fetch
        .apply(tenantId, Map.of("meter", meter, "quantity", Long.toString(quantity)))
        .flatMap(Quotas::parse);
  }

  /**
   * Refuses {@code quantity} more of a meter when the business's plan puts a hard ceiling in the
   * way, naming the figures so the answer says what to do.
   *
   * @param what what is counted, in the plural, as a person would say it
   * @throws ApiException 409 {@code USAGE_QUOTA_REACHED}
   */
  public void requireRoom(UUID tenantId, String meter, long quantity, String what) {
    Optional<Answer> answer = ask(tenantId, meter, quantity);
    if (answer.isEmpty() || answer.get().allowed()) return;
    Answer a = answer.get();
    throw ApiException.conflict(
        "USAGE_QUOTA_REACHED",
        "This plan includes "
            + a.included()
            + " "
            + what
            + " a period and "
            + a.used()
            + " are used; "
            + quantity
            + " more would pass it. A larger plan, or the next period, makes room");
  }

  /** Reads tenant-svc's answer; anything unexpected is no answer, which is allowed. */
  static Optional<Answer> parse(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return Optional.empty();
      JsonObject d = root.getJsonObject("data");
      if (!d.containsKey("allowed")) return Optional.empty();
      Long included =
          d.containsKey("included") && !d.isNull("included")
              ? d.getJsonNumber("included").longValue()
              : null;
      long used = d.containsKey("used") ? d.getJsonNumber("used").longValue() : 0L;
      return Optional.of(new Answer(d.getBoolean("allowed"), used, included));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
