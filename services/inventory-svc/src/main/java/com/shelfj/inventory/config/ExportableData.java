package com.shelfj.inventory.config;

import com.shelfj.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * What inventory-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): stock,
 * batches, movements, counts, planning, food safety records and recalls. Every table and column of
 * the inventory schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "inventory";
  }
}
