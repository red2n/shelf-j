package com.shelfj.order.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for order-svc — extends {@link BaseServiceConfig} for the 9 common properties.
 *
 * <p>Adds the order-specific safety switches: whether prices are resolved from pricing-svc rather
 * than trusted from the client, whether ONLINE orders hold stock, the per-role discount ceilings,
 * and the fallback currency. The two {@code enforce} flags default to the secure setting and warn
 * loudly at boot when overridden.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  private static final Logger LOG = System.getLogger(ServiceConfig.class.getName());

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "order-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8007")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "order")
  String dbSchema;

  /**
   * When true, placeOrder resolves every line's unit price from pricing-svc and ignores the
   * client-supplied unitPrice. Defaults to true (secure). Override to false only in local dev rigs
   * that have no seeded price catalogue (docker-compose sets SHELFJ_ORDER_PRICING_ENFORCE=false).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.pricing.enforce", defaultValue = "true")
  boolean pricingEnforce;

  /**
   * Whether prices are resolved from pricing-svc rather than taken from the client.
   *
   * @return {@code true} unless overridden; {@code false} means client-supplied prices are trusted
   */
  public boolean pricingEnforce() {
    return pricingEnforce;
  }

  /**
   * Fallback currency for a tenant whose {@code TenantCreated} has not been projected yet — a
   * tenant onboarded before the projection existed, or plain event-delivery lag. This is the ONLY
   * currency literal left in the service; every money-bearing write resolves through {@code
   * OrderService.resolveCurrency}, which prefers the tenant's own projected currency.
   *
   * <p>GBP matches the default every other service already uses (pricing, purchase, customer); the
   * three divergent literals this replaces were the bug (SJ-D2).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.currency.default", defaultValue = "GBP")
  String defaultCurrency;

  /**
   * The fallback currency described above.
   *
   * @return the configured fallback, {@code GBP} unless overridden
   */
  public String defaultCurrency() {
    return defaultCurrency;
  }

  /**
   * Per-role ceiling on a manual order discount, as a percentage of subtotal (SJ-D6).
   *
   * <p>A discount is the most common internal-theft vector at a till, so retail tiers the
   * authority: a cashier may take a little off, a manager may take a lot, and anything beyond a
   * role's ceiling needs someone more senior to ring it. Format is {@code ROLE:percent} pairs; a
   * role absent from the map may not discount at all. A caller with several roles gets the highest
   * ceiling among them, and that role is what lands in the audit row.
   */
  @Inject
  @ConfigProperty(
      name = "shelfj.order.discount.max-percent",
      defaultValue = "CASHIER:10,STOREKEEPER:10,MANAGER:50,OWNER:100,PLATFORM_ADMIN:100")
  String discountMaxPercentCfg;

  private Map<String, BigDecimal> discountCeilings = Map.of();

  /**
   * The parsed per-role discount ceilings described above.
   *
   * @return an immutable role → maximum percentage map; a role absent from it may not discount
   */
  public Map<String, BigDecimal> discountCeilings() {
    return discountCeilings;
  }

  /**
   * Parses the ceiling map once at startup and fails the boot on a malformed or out-of-range entry.
   * A silently-dropped entry would mean a role quietly loses its discount authority, which surfaces
   * as a confusing 403 at a till rather than as a config error.
   */
  void parseDiscountCeilings() {
    Map<String, BigDecimal> parsed = new LinkedHashMap<>();
    for (String pair : discountMaxPercentCfg.split(",")) {
      String entry = pair.trim();
      if (entry.isEmpty()) continue;
      int colon = entry.indexOf(':');
      if (colon < 0)
        throw new IllegalStateException(
            "shelfj.order.discount.max-percent entry is not ROLE:percent — got: " + entry);
      String role = entry.substring(0, colon).trim().toUpperCase(Locale.ROOT);
      BigDecimal percent;
      try {
        percent = new BigDecimal(entry.substring(colon + 1).trim());
      } catch (NumberFormatException e) {
        throw new IllegalStateException(
            "shelfj.order.discount.max-percent has a non-numeric percent for " + role, e);
      }
      if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0)
        throw new IllegalStateException(
            "shelfj.order.discount.max-percent for " + role + " must be 0-100 — got: " + percent);
      parsed.put(role, percent);
    }
    discountCeilings = Map.copyOf(parsed);
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * When true, placeOrder holds stock in inventory-svc for every ONLINE order line and rejects the
   * order when stock is short or inventory-svc is unreachable (fail-closed). Override to false only
   * in local dev rigs with no seeded inventory.
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.inventory.reserve-enforce", defaultValue = "true")
  boolean reserveEnforce;

  /**
   * Whether ONLINE orders hold stock in inventory-svc before being accepted.
   *
   * @return {@code true} unless overridden; {@code false} means concurrent checkouts can oversell
   */
  public boolean reserveEnforce() {
    return reserveEnforce;
  }

  /**
   * Lifetime of the stock holds placed at ONLINE checkout. Long by design: a pay-later order can
   * sit PENDING for hours before staff confirm it. Expired holds are reclaimed by inventory-svc's
   * reservation sweeper; the order itself stays valid (it just loses its hold).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.inventory.reservation-ttl-seconds", defaultValue = "172800")
  long reservationTtlSeconds;

  /**
   * How long a checkout stock hold lives.
   *
   * @return the hold lifetime in seconds, 48 hours unless overridden
   */
  public long reservationTtlSeconds() {
    return reservationTtlSeconds;
  }

  /**
   * Fires on every boot so a non-dev environment that inherits docker-compose's pricing.enforce=
   * false override (no seeded price catalogue) cannot silently trust client-supplied prices, tax,
   * and discounts without it showing up in the startup log.
   */
  @PostConstruct
  void onStartup() {
    // CDI permits exactly one @PostConstruct per bean, so startup validation and startup warnings
    // share this method. Parsing first means a malformed ceiling map fails the boot before anything
    // depends on it.
    parseDiscountCeilings();
    if (!pricingEnforce) {
      LOG.log(
          Level.WARNING,
          "shelfj.order.pricing.enforce=false — order-svc is TRUSTING client-supplied"
              + " unitPrice/taxAmount/discountAmount instead of resolving them from pricing-svc."
              + " This is only safe for local dev with no seeded price catalogue; it must never be"
              + " set in a staging or production environment.");
    }
    if (!reserveEnforce) {
      LOG.log(
          Level.WARNING,
          "shelfj.order.inventory.reserve-enforce=false — ONLINE orders are placed WITHOUT holding"
              + " stock, so concurrent checkouts can oversell. This is only safe for local dev"
              + " with no seeded inventory; it must never be set in staging or production.");
    }
  }

  /**
   * {@inheritDoc}
   *
   * @return the Consul registration name, {@code order-svc} unless overridden
   */
  @Override
  public String serviceName() {
    return serviceName;
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8007} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return the Postgres schema this service owns, {@code order} unless overridden
   */
  @Override
  public String dbSchema() {
    return dbSchema;
  }
}
