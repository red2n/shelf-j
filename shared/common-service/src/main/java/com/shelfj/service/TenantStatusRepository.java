package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Local projection of tenant operational status, fed by {@code TenantStatusChanged} Kafka events.
 * Services inject this to gate operations without a synchronous call to tenant-svc. Fail-open: a
 * missing row is treated as ACTIVE.
 */
@ApplicationScoped
public class TenantStatusRepository extends BaseJdbcRepository {

  /**
   * Applies the projection write and reports whether it actually took effect. The {@code WHERE}
   * clause silently no-ops a stale/out-of-order event (an older {@code changedAt} than what's
   * already stored), which callers need to know — logging "updated" regardless would misrepresent
   * the actual projection state after a reordered redelivery.
   *
   * @param tenantId the tenant whose status changed
   * @param status the new status, e.g. {@code "ACTIVE"}, {@code "SUSPENDED"}
   * @param changedAt when the status change occurred, per the source event's {@code occurredAt}
   * @return {@code true} if the row was inserted or updated; {@code false} if an existing, newer
   *     row was left untouched.
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean upsertTenantStatus(UUID tenantId, String status, Instant changedAt) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO tenant_status (tenant_id, status, status_changed_at)"
                    + " VALUES (?,?,?)"
                    + " ON CONFLICT (tenant_id) DO UPDATE SET"
                    + "   status = EXCLUDED.status,"
                    + "   status_changed_at = EXCLUDED.status_changed_at"
                    + " WHERE EXCLUDED.status_changed_at >= tenant_status.status_changed_at")) {
      ps.setObject(1, tenantId);
      ps.setString(2, status);
      ps.setObject(3, changedAt.atOffset(ZoneOffset.UTC));
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("upsert tenant status", e);
    }
  }

  /**
   * @param tenantId the tenant to check
   * @return {@code true} if the projection has no row for {@code tenantId} (fail-open) or the
   *     stored status is {@code "ACTIVE"} (case-insensitive); {@code false} otherwise
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean isActive(UUID tenantId) {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement("SELECT status FROM tenant_status WHERE tenant_id = ?")) {
      ps.setObject(1, tenantId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) return true;
        return "ACTIVE".equalsIgnoreCase(rs.getString("status"));
      }
    } catch (SQLException e) {
      throw dbError("check tenant status", e);
    }
  }

  /**
   * Projects the tenant's trading currency from a {@code TenantCreated} event, deduped on {@code
   * eventId} in the same transaction as the write so a redelivery is a no-op (golden rule #7).
   *
   * <p>Upserts onto the same row {@link #upsertTenantStatus} maintains: a tenant's currency and its
   * operational status are both tenant-level facts fed by tenant-svc events, and keeping them on
   * one row means the checkout path reads both in a single lookup. The status column keeps its
   * schema default when this event arrives first, so ordering between the two events does not
   * matter.
   *
   * @param eventId the source event's id, used for consumer-level deduplication
   * @param consumer the consumer identity recorded against {@code eventId}
   * @param tenantId the tenant whose currency is being projected
   * @param currency ISO-4217 alpha-3 code, stored upper-cased
   * @return {@code true} if this call applied the projection; {@code false} if the event had
   *     already been processed by this consumer
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean projectTenantCurrencyOnce(
      UUID eventId, String consumer, UUID tenantId, String currency) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO tenant_status (tenant_id, currency)"
                      + " VALUES (?,?)"
                      + " ON CONFLICT (tenant_id) DO UPDATE SET currency = EXCLUDED.currency")) {
            ps.setObject(1, tenantId);
            ps.setString(2, currency.toUpperCase(Locale.ROOT));
            ps.executeUpdate();
          }
          return true;
        },
        "project tenant currency");
  }

  /**
   * @param tenantId the tenant to look up
   * @return the projected ISO-4217 currency, or empty when no {@code TenantCreated} has been
   *     projected for this tenant yet (a tenant onboarded before the projection existed, or
   *     event-delivery lag). Callers fall back to their configured default rather than guessing.
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public Optional<String> findCurrency(UUID tenantId) {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement("SELECT currency FROM tenant_status WHERE tenant_id = ?")) {
      ps.setObject(1, tenantId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        String currency = rs.getString("currency");
        return currency == null || currency.isBlank()
            ? Optional.empty()
            : Optional.of(currency.trim().toUpperCase(Locale.ROOT));
      }
    } catch (SQLException e) {
      throw dbError("read tenant currency", e);
    }
  }
}
