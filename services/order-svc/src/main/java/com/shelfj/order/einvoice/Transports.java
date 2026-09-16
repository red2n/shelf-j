package com.shelfj.order.einvoice;

import com.shelfj.order.domain.EInvoiceTransports;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves an {@link EInvoiceTransport} by network and provider name. Every deployment has the
 * simulated provider on every network; a real one appears when its module is deployed, and can be
 * chosen once its credentials are configured.
 */
@ApplicationScoped
public class Transports {

  @Inject Instance<EInvoiceTransport> discovered;

  private final Map<String, EInvoiceTransport> byKey = new HashMap<>();

  @PostConstruct
  void init() {
    for (EInvoiceTransport t : discovered) {
      for (String network : t.networks()) {
        byKey.put(key(network, t.name()), t);
      }
    }
  }

  /**
   * @return the provider serving the network under that name, or null when none is deployed
   */
  public EInvoiceTransport forNetwork(String network, String provider) {
    return network == null || provider == null ? null : byKey.get(key(network, provider));
  }

  /** The providers deployed for each network, sorted. */
  public Map<String, List<String>> providers() {
    Map<String, List<String>> out = new TreeMap<>();
    for (String network : EInvoiceTransports.NETWORKS) {
      out.put(
          network,
          byKey.entrySet().stream()
              .filter(e -> e.getKey().startsWith(network + ":"))
              .map(e -> e.getValue().name())
              .sorted()
              .toList());
    }
    return out;
  }

  /** The providers a business can choose for each network here: deployed and configured. */
  public Map<String, List<String>> available() {
    Map<String, List<String>> out = new TreeMap<>();
    for (String network : EInvoiceTransports.NETWORKS) {
      out.put(
          network,
          byKey.entrySet().stream()
              .filter(e -> e.getKey().startsWith(network + ":"))
              .filter(e -> e.getValue().isConfigured())
              .map(e -> e.getValue().name())
              .sorted()
              .toList());
    }
    return out;
  }

  /** The providers that take the business's own credentials, by network. */
  public Map<String, List<String>> needingSecret() {
    Map<String, List<String>> out = new TreeMap<>();
    for (String network : EInvoiceTransports.NETWORKS) {
      out.put(
          network,
          byKey.entrySet().stream()
              .filter(e -> e.getKey().startsWith(network + ":"))
              .filter(e -> e.getValue().needsSecret())
              .map(e -> e.getValue().name())
              .sorted()
              .toList());
    }
    return out;
  }

  private static String key(String network, String provider) {
    return network.toUpperCase(Locale.ROOT) + ":" + provider.trim().toUpperCase(Locale.ROOT);
  }
}
