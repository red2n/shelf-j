package com.shelfj.iam.config;

import com.shelfj.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * What iam-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): staff logins,
 * their roles at each store, till sessions and the audit log. Every table and column of the iam
 * schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "iam";
  }

  @Override
  public Map<String, String> excludedTables() {
    String secondFactor =
        "a login's second factor: a credential; it is set up again at the destination";
    return Map.of(
        "mfa_totp",
        secondFactor,
        "mfa_recovery_codes",
        secondFactor,
        "mfa_passkeys",
        secondFactor,
        "mfa_challenges",
        "sign-ins waiting on a second factor, minutes old: not data",
        "otp_codes",
        "one-time sign-in codes sent to a phone or email address and valid for minutes: not data, and not tied to a business",
        "refresh_tokens",
        "login session tokens: a credential",
        "roles",
        "the platform's built-in roles, the same for every business; a business's own roles are in tenant-svc",
        "signing_keys",
        "the platform's token signing keys: a credential of the deployment, not a business's data");
  }

  @Override
  public Map<String, String> excludedColumns() {
    return Map.of(
        "users.password_hash",
        "a staff member's password hash: a credential; imported staff set a password at the destination");
  }

  @Override
  public Map<String, String> tenantPredicates() {
    return Map.of("user_roles", "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)");
  }

  @Override
  public Map<String, String> erasurePredicates() {
    String staffOfTheBusiness = "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)";
    return Map.of(
        "refresh_tokens", staffOfTheBusiness,
        "mfa_totp", staffOfTheBusiness,
        "mfa_recovery_codes", staffOfTheBusiness,
        "mfa_passkeys", staffOfTheBusiness,
        "mfa_challenges", staffOfTheBusiness);
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
