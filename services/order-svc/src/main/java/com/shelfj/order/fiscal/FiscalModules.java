package com.shelfj.order.fiscal;

import com.shelfj.order.domain.Domain.FiscalStoreSettings;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the {@link FiscalRegimeModule} for a regime code, and is the only place that knows which
 * regimes are deployed. Modules are discovered through CDI, so adding one means adding a class.
 */
@ApplicationScoped
public class FiscalModules {

  @Inject Instance<FiscalRegimeModule> discovered;

  private final Map<String, FiscalRegimeModule> byRegime = new HashMap<>();

  @PostConstruct
  void init() {
    for (FiscalRegimeModule m : discovered) {
      byRegime.put(m.regime().toUpperCase(Locale.ROOT), m);
    }
    if (!byRegime.containsKey(FiscalStoreSettings.REGIME_NONE)) {
      throw new IllegalStateException("the NONE fiscal module is missing");
    }
  }

  /**
   * @param regime a regime code, case-insensitive
   * @return the module, or null when no such regime is deployed
   */
  public FiscalRegimeModule forRegime(String regime) {
    return regime == null ? null : byRegime.get(regime.trim().toUpperCase(Locale.ROOT));
  }

  /** The module for a store with no settings, or with settings naming a regime — never null. */
  public FiscalRegimeModule forSettings(FiscalStoreSettings settings) {
    FiscalRegimeModule m = forRegime(settings == null ? null : settings.regime());
    return m == null ? byRegime.get(FiscalStoreSettings.REGIME_NONE) : m;
  }

  /**
   * @return every deployed regime code, NONE first
   */
  public java.util.List<String> regimes() {
    return FiscalStoreSettings.REGIMES.stream().filter(byRegime::containsKey).toList();
  }
}
