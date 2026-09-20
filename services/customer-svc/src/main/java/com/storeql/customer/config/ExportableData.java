package com.storeql.customer.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What customer-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): customers,
 * their addresses, loyalty and store credit, and their marketing choices. Every table and column of
 * the customer schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "customer";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.of(
        "marketing_unsubscribe_tokens",
        "one-click unsubscribe tokens: a credential that lets whoever holds a link change a shopper's choices; the next message carries a new one");
  }
}
