package com.shelfj.pricing.config;

import com.shelfj.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * What pricing-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): price lists
 * and their history, promotions, markdowns, VAT and the prior-price ledger. Every table and column
 * of the pricing schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "pricing";
  }

  @Override
  public Map<String, String> excludedColumns() {
    return Map.of(
        "vat_registrations.hmrc_access_token",
        "an HMRC Making Tax Digital access token: a credential; the business authorises again",
        "vat_registrations.hmrc_refresh_token",
        "an HMRC Making Tax Digital refresh token: a credential; the business authorises again");
  }

  @Override
  public Set<String> derivedTables() {
    return Set.of("catalogue_products", "catalogue_variants");
  }
}
