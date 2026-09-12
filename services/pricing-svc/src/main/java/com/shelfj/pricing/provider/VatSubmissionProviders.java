package com.shelfj.pricing.provider;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves a {@link VatSubmissionProvider} by name; the choice is per tenant registration. */
@ApplicationScoped
public class VatSubmissionProviders {

  @Inject Instance<VatSubmissionProvider> discovered;

  private final Map<String, VatSubmissionProvider> byName = new HashMap<>();

  @PostConstruct
  void init() {
    for (VatSubmissionProvider p : discovered) {
      byName.put(p.name().toUpperCase(Locale.ROOT), p);
    }
  }

  /**
   * @param name provider name, case-insensitive
   * @return the provider, or null if none of that name is deployed
   */
  public VatSubmissionProvider forName(String name) {
    return name == null ? null : byName.get(name.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * @return the providers a tenant may register with here: deployed and configured
   */
  public List<String> available() {
    return byName.values().stream()
        .filter(VatSubmissionProvider::isConfigured)
        .map(VatSubmissionProvider::name)
        .sorted()
        .toList();
  }
}
