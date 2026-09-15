package com.shelfj.product.config;

import com.shelfj.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What product-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the
 * catalogue: products, variants, categories, brands, images, allergens declared and safety
 * information. Every table and column of the product schema is exported except what is named here,
 * with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "product";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.of(
        "age_restriction_rules",
        "the platform's reference data, the same for every business",
        "allergens",
        "the platform's reference data, the same for every business",
        "item_attribute_group_fields",
        "the platform's reference data, the same for every business",
        "item_attribute_groups",
        "the platform's reference data, the same for every business",
        "uom_classes",
        "the platform's reference data, the same for every business",
        "uom_definitions",
        "the platform's reference data, the same for every business",
        "uom_standard_conversions",
        "the platform's reference data, the same for every business");
  }
}
