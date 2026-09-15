package com.shelfj.tenant.config;

import com.shelfj.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What tenant-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the business,
 * its stores, zones, staff assignments, roles, retention schedule and the notices it was sent.
 * Every table and column of the tenant schema is exported except what is named here, with the
 * reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "tenant";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.of(
        "incident_reporting_stages",
        "the platform's reference data: the law as recorded for every business",
        "jurisdiction_members",
        "the platform's reference data: the law as recorded for every business",
        "jurisdiction_regimes",
        "the platform's reference data: the law as recorded for every business",
        "legal_obligations",
        "the platform's reference data: the law as recorded for every business",
        "retention_classes",
        "the platform's reference data: the law as recorded for every business",
        "retention_floors",
        "the platform's reference data: the law as recorded for every business",
        "security_incident_events",
        "the platform's own incident register; what the business was told is in security_notices",
        "security_incidents",
        "the platform's own incident register; what the business was told is in security_notices");
  }

  @Override
  public Map<String, String> tenantPredicates() {
    return Map.of("tenants", "id = ?");
  }

  @Override
  public Map<String, String> importSkipped() {
    return Map.of(
        "security_incident_tenants",
        "links to the platform's incident register, which is not ported",
        "tenant_erasure_evidence",
        "the evidence of the leaving business's erasure, never the importing one's",
        "tenant_switches",
        "the leaving business's notice; imported, it would put the importing business on notice",
        "tenants",
        "the destination business has its own record, made when it signs up");
  }

  @Override
  public Map<String, String> keptAtErasure() {
    return Map.of(
        "tenant_erasure_evidence",
        "what each service erased, kept as the platform's record of the erasure",
        "tenant_switches",
        "the notice and its dates, kept as the platform's record that the business left",
        "tenants",
        "the business's name and status, kept as the record that it was a customer and left");
  }
}
