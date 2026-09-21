package com.storeql.notification.repo;

import com.storeql.ids.Ids;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.TemplateStore;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A business's message templates and settings. Every read and write is by the tenant first; a
 * template is never updated in place except to be retired, and a new version is written in the same
 * transaction as the old one is retired, so a message is never without words to go out in.
 */
@ApplicationScoped
public class TemplateRepository extends BaseJdbcRepository implements TemplateStore {

  private static final String COLS =
      "id, message_type, form, language, version, subject, body, created_at, created_by,"
          + " retired_at";

  @Override
  public Optional<Stored> live(
      UUID tenantId, String messageType, Catalogue.Form form, String language) {
    return query(
            "SELECT "
                + COLS
                + " FROM message_templates WHERE tenant_id = ? AND message_type = ? AND form = ?"
                + " AND language = ? AND retired_at IS NULL",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, messageType);
              ps.setString(3, form.name());
              ps.setString(4, language);
            },
            TemplateRepository::stored,
            "load live template")
        .stream()
        .findFirst();
  }

  /** Every live template of a business, for the catalogue view. */
  public List<Stored> liveAll(UUID tenantId) {
    return query(
        "SELECT "
            + COLS
            + " FROM message_templates WHERE tenant_id = ? AND retired_at IS NULL"
            + " ORDER BY message_type, form, language",
        ps -> ps.setObject(1, tenantId),
        TemplateRepository::stored,
        "list live templates");
  }

  /** Every version of one message, form and language, newest first. */
  public List<Stored> history(
      UUID tenantId, String messageType, Catalogue.Form form, String language, int limit) {
    return query(
        "SELECT "
            + COLS
            + " FROM message_templates WHERE tenant_id = ? AND message_type = ? AND form = ?"
            + " AND language = ? ORDER BY version DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, messageType);
          ps.setString(3, form.name());
          ps.setString(4, language);
          ps.setInt(5, limit);
        },
        TemplateRepository::stored,
        "template history");
  }

  /**
   * Writes the next version and retires the live one, in one transaction. Two saves racing each
   * other both read the same highest version, and the unique index lets one through.
   */
  public Stored publish(
      UUID tenantId,
      String messageType,
      Catalogue.Form form,
      String language,
      String subject,
      String body,
      UUID by,
      Instant now) {
    return inTx(
        c -> {
          int next;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT COALESCE(MAX(version), 0) + 1 FROM message_templates"
                      + " WHERE tenant_id = ? AND message_type = ? AND form = ? AND language = ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, messageType);
            ps.setString(3, form.name());
            ps.setString(4, language);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              next = rs.getInt(1);
            }
          }
          retireTx(c, tenantId, messageType, form, language, by, now);
          UUID id = Ids.newId();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO message_templates (id, tenant_id, message_type, form, language,"
                      + " version, subject, body, created_at, created_by)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, messageType);
            ps.setString(4, form.name());
            ps.setString(5, language);
            ps.setInt(6, next);
            ps.setString(7, subject);
            ps.setString(8, body);
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setObject(10, by);
            ps.executeUpdate();
          }
          return new Stored(id, messageType, form, language, next, subject, body, now, by, null);
        },
        "publish template");
  }

  /**
   * Retires the live version: the message goes back to the platform's words.
   *
   * @return whether there was one to retire
   */
  public boolean retire(
      UUID tenantId,
      String messageType,
      Catalogue.Form form,
      String language,
      UUID by,
      Instant now) {
    return inTx(c -> retireTx(c, tenantId, messageType, form, language, by, now) > 0, "retire");
  }

  private static int retireTx(
      java.sql.Connection c,
      UUID tenantId,
      String messageType,
      Catalogue.Form form,
      String language,
      UUID by,
      Instant now)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE message_templates SET retired_at = ?, retired_by = ?"
                + " WHERE tenant_id = ? AND message_type = ? AND form = ? AND language = ?"
                + " AND retired_at IS NULL")) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setObject(2, by);
      ps.setObject(3, tenantId);
      ps.setString(4, messageType);
      ps.setString(5, form.name());
      ps.setString(6, language);
      return ps.executeUpdate();
    }
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new ApiException(
          409,
          "TEMPLATE_SAVED_MEANWHILE",
          "Somebody saved this template at the same moment: open it again and save",
          List.of(),
          e);
    }
    return super.handleTxSqlException(what, e);
  }

  // ── settings ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public Optional<Settings> settings(UUID tenantId) {
    return query(
            "SELECT default_language, sign_off FROM message_settings WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> new Settings(rs.getString(1), rs.getString(2)),
            "load message settings")
        .stream()
        .findFirst();
  }

  public void putSettings(
      UUID tenantId, String defaultLanguage, String signOff, UUID by, Instant now) {
    exec(
        "INSERT INTO message_settings (tenant_id, default_language, sign_off, updated_at,"
            + " updated_by) VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET"
            + " default_language = EXCLUDED.default_language, sign_off = EXCLUDED.sign_off,"
            + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, defaultLanguage);
          ps.setString(3, signOff);
          ps.setTimestamp(4, Timestamp.from(now));
          ps.setObject(5, by);
        },
        "put message settings");
  }

  private static Stored stored(ResultSet rs) throws SQLException {
    OffsetDateTime retired = rs.getObject("retired_at", OffsetDateTime.class);
    return new Stored(
        rs.getObject("id", UUID.class),
        rs.getString("message_type"),
        Catalogue.Form.valueOf(rs.getString("form")),
        rs.getString("language"),
        rs.getInt("version"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class),
        retired == null ? null : retired.toInstant());
  }
}
