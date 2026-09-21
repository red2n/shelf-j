package com.storeql.notification.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What notification-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): the
 * notification log and shortage alerts. Every table and column of the notification schema is
 * exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "notification";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.of(
        "push_devices",
        "device push tokens: a credential for sending to a phone; devices register again at the destination");
  }
}
