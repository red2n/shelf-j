package com.storeql.cart.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * What cart-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): carts and their
 * lines. Every table and column of the cart schema is exported except what is named here, with the
 * reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "cart";
  }

  @Override
  public Map<String, String> importSkipped() {
    return Map.of(
        "tenant_status", "the destination business's own status is kept, set when it signs up");
  }

  @Override
  public Set<String> derivedTables() {
    return Set.of("store_status", "tenant_status");
  }
}
