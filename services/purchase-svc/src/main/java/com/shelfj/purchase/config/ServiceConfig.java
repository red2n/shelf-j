package com.shelfj.purchase.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8009")
  int servicePort;

  @Override
  public String serviceName() {
    return "purchase-svc";
  }

  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * Fallback currency for a tenant whose declared currency cannot be read — tenant-svc unreachable,
   * or no such tenant. The last resort, not the normal path: every money-bearing row in this
   * service resolves through {@code PurchaseService.resolveTenantCurrency}, which prefers what the
   * tenant actually declared at onboarding.
   *
   * <p>It replaces three hardcoded {@code "GBP"} literals — supplier, purchase order and
   * intercompany invoice (SJ-D23). That is SJ-D2's defect exactly, in the one service SJ-D2's sweep
   * never reached: a multi-currency platform stamping one country's currency onto another tenant's
   * money.
   */
  @Inject
  @ConfigProperty(name = "shelfj.purchase.currency.default", defaultValue = "GBP")
  String defaultCurrency;

  public String defaultCurrency() {
    return defaultCurrency;
  }

  @Override
  public String dbSchema() {
    return "purchase";
  }
}
