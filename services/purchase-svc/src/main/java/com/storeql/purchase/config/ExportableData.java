package com.storeql.purchase.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * What purchase-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): suppliers,
 * purchase orders, receipts, invoices, returns, payment runs and the nominal ledger. Every table
 * and column of the purchase schema is exported except what is named here, with the reason the
 * register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "purchase";
  }
}
