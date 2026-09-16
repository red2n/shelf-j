package com.shelfj.order.repo;

import com.shelfj.order.domain.EInvoiceTransports.Settings;
import com.shelfj.order.domain.EInvoiceTransports.Transmission;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A business's choice of network, and every attempt to send a document over it. */
@ApplicationScoped
public class EInvoiceTransportRepository extends BaseJdbcRepository {

  private static final String SETTINGS_COLUMNS =
      "tenant_id, network, provider, provider_account, updated_at, updated_by";

  private static final String COLUMNS =
      "id, tenant_id, invoice_id, network, provider, status, attempts, next_attempt_at, receiver,"
          + " provider_ref, detail, response, created_at, updated_at, sent_at, settled_at,"
          + " created_by";

  private static final String FIND_SETTINGS =
      "SELECT " + SETTINGS_COLUMNS + " FROM einvoice_transport_settings WHERE tenant_id = ?";

  private static final String UPSERT_SETTINGS =
      "INSERT INTO einvoice_transport_settings (tenant_id, network, provider, provider_account,"
          + " updated_by) VALUES (?,?,?,?,?) ON CONFLICT (tenant_id) DO UPDATE SET"
          + " network = EXCLUDED.network, provider = EXCLUDED.provider,"
          + " provider_account = EXCLUDED.provider_account, updated_by = EXCLUDED.updated_by,"
          + " updated_at = now()";

  private static final String INSERT =
      "INSERT INTO sales_invoice_transmissions (id, tenant_id, invoice_id, network, provider,"
          + " status, attempts, next_attempt_at, receiver, created_by)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?)";

  private static final String BY_ID =
      "SELECT " + COLUMNS + " FROM sales_invoice_transmissions WHERE tenant_id = ? AND id = ?";

  private static final String BY_INVOICE =
      "SELECT "
          + COLUMNS
          + " FROM sales_invoice_transmissions WHERE tenant_id = ? AND invoice_id = ?"
          + " ORDER BY created_at DESC, id DESC";

  private static final String LATEST_BY_INVOICES =
      "SELECT DISTINCT ON (invoice_id) "
          + COLUMNS
          + " FROM sales_invoice_transmissions WHERE tenant_id = ? AND invoice_id = ANY(?)"
          + " ORDER BY invoice_id, created_at DESC, id DESC";

  private static final String LIST =
      "SELECT "
          + COLUMNS
          + " FROM sales_invoice_transmissions s WHERE s.tenant_id = ?"
          + " AND (?::text IS NULL OR s.status = ?)"
          + " AND (?::uuid IS NULL OR (s.created_at, s.id) < (SELECT p.created_at, p.id"
          + " FROM sales_invoice_transmissions p WHERE p.tenant_id = ? AND p.id = ?))"
          + " ORDER BY s.created_at DESC, s.id DESC LIMIT ?";

  // Due rows are taken under SKIP LOCKED: two workers never send one document twice.
  private static final String CLAIM_DUE =
      "UPDATE sales_invoice_transmissions SET status = 'SENDING', attempts = attempts + 1,"
          + " updated_at = now() WHERE id IN (SELECT id FROM sales_invoice_transmissions"
          + " WHERE status IN ('QUEUED', 'PENDING') AND next_attempt_at <= now()"
          + " ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING "
          + COLUMNS;

  private static final String SETTLE =
      "UPDATE sales_invoice_transmissions SET status = ?, next_attempt_at = ?, provider_ref ="
          + " COALESCE(?, provider_ref), detail = ?, response = COALESCE(?::jsonb, response),"
          + " sent_at = COALESCE(sent_at, ?), settled_at = ?, updated_at = now()"
          + " WHERE tenant_id = ? AND id = ?";

  public Optional<Settings> findSettings(UUID tenantId) {
    return first(
        query(
            FIND_SETTINGS,
            ps -> ps.setObject(1, tenantId),
            EInvoiceTransportRepository::mapSettings,
            "find e-invoice transport settings"));
  }

  public void upsertSettings(Settings s) {
    exec(
        UPSERT_SETTINGS,
        ps -> {
          ps.setObject(1, s.tenantId());
          ps.setString(2, s.network());
          ps.setString(3, s.provider());
          ps.setString(4, s.providerAccount());
          ps.setObject(5, s.updatedBy());
        },
        "upsert e-invoice transport settings");
  }

  /**
   * Records a document to be sent: queued for the worker, or already taken ({@code SENDING}, one
   * attempt) by the caller about to send it itself.
   */
  public void enqueue(Transmission t) {
    exec(
        INSERT,
        ps -> {
          ps.setObject(1, t.id());
          ps.setObject(2, t.tenantId());
          ps.setObject(3, t.invoiceId());
          ps.setString(4, t.network());
          ps.setString(5, t.provider());
          ps.setString(6, t.status());
          ps.setInt(7, t.attempts());
          ps.setObject(8, t.nextAttemptAt().atOffset(ZoneOffset.UTC));
          ps.setString(9, t.receiver());
          ps.setObject(10, t.createdBy());
        },
        "queue e-invoice transmission");
  }

  public Optional<Transmission> find(UUID tenantId, UUID id) {
    return first(
        query(
            BY_ID,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            EInvoiceTransportRepository::map,
            "find e-invoice transmission"));
  }

  /** Every attempt for a document, newest first. */
  public List<Transmission> byInvoice(UUID tenantId, UUID invoiceId) {
    return query(
        BY_INVOICE,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, invoiceId);
        },
        EInvoiceTransportRepository::map,
        "list e-invoice transmissions of a document");
  }

  /** The newest attempt for each of the documents, by document. */
  public Map<UUID, Transmission> latestByInvoices(UUID tenantId, List<UUID> invoiceIds) {
    Map<UUID, Transmission> out = new HashMap<>();
    if (invoiceIds.isEmpty()) return out;
    for (Transmission t :
        query(
            LATEST_BY_INVOICES,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", invoiceIds.toArray()));
            },
            EInvoiceTransportRepository::map,
            "latest e-invoice transmissions")) {
      out.put(t.invoiceId(), t);
    }
    return out;
  }

  /**
   * Transmissions newest first, a page at a time.
   *
   * @param status only this status, or null for all
   * @param after the last row of the previous page, or null for the first page
   */
  public List<Transmission> list(UUID tenantId, String status, UUID after, int limit) {
    return query(
        LIST,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setString(3, status);
          ps.setObject(4, after);
          ps.setObject(5, tenantId);
          ps.setObject(6, after);
          ps.setInt(7, limit);
        },
        EInvoiceTransportRepository::map,
        "list e-invoice transmissions");
  }

  /** Takes up to {@code limit} due rows for sending, marking them so no other worker does. */
  public List<Transmission> claimDue(int limit) {
    return query(
        CLAIM_DUE,
        ps -> ps.setInt(1, limit),
        EInvoiceTransportRepository::map,
        "claim due transmissions");
  }

  /**
   * Records what the network said.
   *
   * @param nextAttemptAt when to try or ask again; for a settled row, now
   * @param providerRef the network's reference, kept once known
   * @param response the network's answer as JSON, kept once given
   * @param sentAt when the network first took the document, kept once set
   * @param settledAt when it answered for good, or null while it has not
   */
  public void settle(
      UUID tenantId,
      UUID id,
      String status,
      Instant nextAttemptAt,
      String providerRef,
      String detail,
      String response,
      Instant sentAt,
      Instant settledAt) {
    exec(
        SETTLE,
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, nextAttemptAt.atOffset(ZoneOffset.UTC));
          ps.setString(3, providerRef);
          ps.setString(4, detail);
          ps.setString(5, response);
          ps.setObject(6, sentAt == null ? null : sentAt.atOffset(ZoneOffset.UTC));
          ps.setObject(7, settledAt == null ? null : settledAt.atOffset(ZoneOffset.UTC));
          ps.setObject(8, tenantId);
          ps.setObject(9, id);
        },
        "settle e-invoice transmission");
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static Settings mapSettings(ResultSet rs) throws SQLException {
    return new Settings(
        rs.getObject("tenant_id", UUID.class),
        rs.getString("network"),
        rs.getString("provider"),
        rs.getString("provider_account"),
        instant(rs, "updated_at"),
        rs.getObject("updated_by", UUID.class));
  }

  private static Transmission map(ResultSet rs) throws SQLException {
    return new Transmission(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getString("network"),
        rs.getString("provider"),
        rs.getString("status"),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
        rs.getString("receiver"),
        rs.getString("provider_ref"),
        rs.getString("detail"),
        rs.getString("response"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "sent_at"),
        instant(rs, "settled_at"),
        rs.getObject("created_by", UUID.class));
  }
}
