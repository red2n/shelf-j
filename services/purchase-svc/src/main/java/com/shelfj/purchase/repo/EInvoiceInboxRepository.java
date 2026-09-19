package com.shelfj.purchase.repo;

import com.shelfj.purchase.domain.EInvoiceInbox.Settings;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Where a business fetches its invoices from, and how far the last fetch reached (07.13). */
@ApplicationScoped
public class EInvoiceInboxRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "tenant_id, network, provider, provider_account, provider_secret, fetched_to,"
          + " last_fetch_at, last_fetch_note, updated_at, updated_by";

  public Optional<Settings> find(UUID tenantId) {
    return query(
            "SELECT " + COLUMNS + " FROM einvoice_inbox_settings WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            EInvoiceInboxRepository::read,
            "the inbox settings")
        .stream()
        .findFirst();
  }

  /**
   * Writes the settings.
   *
   * <p>The credential is only replaced when one is given: {@code COALESCE} on the way in, so
   * leaving it out keeps what is stored and a blank removes it. A screen that cannot show a secret
   * must not be able to erase one by saving the rest of the form.
   */
  public void upsert(Settings s, boolean replaceSecret) {
    exec(
        "INSERT INTO einvoice_inbox_settings (tenant_id, network, provider, provider_account,"
            + " provider_secret, updated_at, updated_by) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id) DO UPDATE SET network = EXCLUDED.network,"
            + " provider = EXCLUDED.provider, provider_account = EXCLUDED.provider_account,"
            + " provider_secret = CASE WHEN ? THEN EXCLUDED.provider_secret"
            + " ELSE einvoice_inbox_settings.provider_secret END,"
            + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
        ps -> {
          ps.setObject(1, s.tenantId());
          ps.setString(2, s.network());
          ps.setString(3, s.provider());
          ps.setString(4, s.providerAccount());
          ps.setString(5, s.providerSecret());
          ps.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(7, s.updatedBy());
          ps.setBoolean(8, replaceSecret);
        },
        "write the inbox settings");
  }

  /** Records what a fetch reached, so the next one asks from there. */
  public void fetched(UUID tenantId, LocalDate fetchedTo, String note) {
    exec(
        "UPDATE einvoice_inbox_settings SET fetched_to = ?, last_fetch_at = ?, last_fetch_note = ?"
            + " WHERE tenant_id = ?",
        ps -> {
          ps.setObject(1, fetchedTo);
          ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setString(3, note);
          ps.setObject(4, tenantId);
        },
        "record a fetch");
  }

  private static Settings read(ResultSet rs) throws SQLException {
    return new Settings(
        rs.getObject("tenant_id", UUID.class),
        rs.getString("network"),
        rs.getString("provider"),
        rs.getString("provider_account"),
        rs.getString("provider_secret"),
        rs.getObject("fetched_to", LocalDate.class),
        instant(rs, "last_fetch_at"),
        rs.getString("last_fetch_note"),
        instant(rs, "updated_at"),
        rs.getObject("updated_by", UUID.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
