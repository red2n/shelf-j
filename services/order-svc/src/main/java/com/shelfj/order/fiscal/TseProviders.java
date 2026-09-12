package com.shelfj.order.fiscal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a {@link TseProvider} by name. Unlike the payment provider, the choice is per store —
 * one tenant's German stores may sign with a cloud module while a test rig simulates — so there is
 * no single active provider, only the ones deployed.
 */
@ApplicationScoped
public class TseProviders {

  @Inject Instance<TseProvider> discovered;

  private final Map<String, TseProvider> byName = new HashMap<>();

  @PostConstruct
  void init() {
    for (TseProvider p : discovered) {
      byName.put(p.name().toUpperCase(Locale.ROOT), p);
    }
  }

  /**
   * @param name provider name, case-insensitive
   * @return the provider, or null if none of that name is deployed
   */
  public TseProvider forName(String name) {
    return name == null ? null : byName.get(name.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * @return the deployed provider names, sorted
   */
  public List<String> names() {
    return byName.keySet().stream().sorted().toList();
  }

  /**
   * @return the providers a store can register a device with here: deployed and configured
   */
  public List<String> available() {
    return byName.values().stream()
        .filter(TseProvider::isConfigured)
        .map(TseProvider::name)
        .sorted()
        .toList();
  }
}
