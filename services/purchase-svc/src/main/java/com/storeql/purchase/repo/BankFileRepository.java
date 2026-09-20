package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Pain002;
import com.storeql.purchase.domain.PayingAccounts.PayingAccount;
import com.storeql.purchase.domain.PaymentRuns.PayeeCheck;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for bank-standard payment files (17.12): the business's paying accounts, the
 * status reports its bank returns and what each said per supplier, and the close matches a manager
 * released. Every query filters by tenant_id first.
 */
@ApplicationScoped
public class BankFileRepository extends BaseJdbcRepository {

  private static final String ACCOUNT_COLUMNS =
      "id,tenant_id,currency,account_name,sort_code,account_number,iban,bic,service_user_number,"
          + "set_by,set_at";

  /** What a report said about one supplier's payment, ready to store. */
  public record StatusRow(UUID supplierId, Pain002.TransactionStatus status) {}

  /**
   * Adds a paying account; its time is the database's, the clock a run's approval is stamped by.
   */
  public void insertPayingAccount(PayingAccount a) {
    exec(
        "INSERT INTO paying_accounts (id,tenant_id,currency,account_name,sort_code,account_number,"
            + "iban,bic,service_user_number,set_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, a.id());
          ps.setObject(2, a.tenantId());
          ps.setString(3, a.currency());
          ps.setString(4, a.accountName());
          ps.setString(5, a.sortCode());
          ps.setString(6, a.accountNumber());
          ps.setString(7, a.iban());
          ps.setString(8, a.bic());
          ps.setString(9, a.serviceUserNumber());
          ps.setObject(10, a.setBy());
        },
        "set paying account");
  }

  /** A currency's paying account in force now: the latest set. */
  public Optional<PayingAccount> findPayingAccount(UUID tenantId, String currency) {
    var rows =
        query(
            "SELECT "
                + ACCOUNT_COLUMNS
                + " FROM paying_accounts WHERE tenant_id = ? AND currency = ?"
                + " ORDER BY set_at DESC, id DESC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, currency);
            },
            BankFileRepository::account,
            "find paying account");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * The paying account a run approved at a moment pays from: the one in force then, or, when there
   * was none yet, the first set after it. Any later account is a change the run was not approved
   * with.
   */
  public Optional<PayingAccount> findPayingAccountForRun(
      UUID tenantId, String currency, Instant approvedAt) {
    var rows =
        query(
            "SELECT "
                + ACCOUNT_COLUMNS
                + " FROM paying_accounts WHERE tenant_id = ? AND currency = ?"
                + " ORDER BY CASE WHEN set_at <= ? THEN 0 ELSE 1 END,"
                + "          CASE WHEN set_at <= ? THEN set_at END DESC NULLS LAST, set_at, id"
                + " LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, currency);
              ps.setObject(3, approvedAt.atOffset(ZoneOffset.UTC));
              ps.setObject(4, approvedAt.atOffset(ZoneOffset.UTC));
            },
            BankFileRepository::account,
            "find paying account for run");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** The paying account in force for each currency. */
  public List<PayingAccount> findPayingAccounts(UUID tenantId) {
    return query(
        "SELECT DISTINCT ON (currency) "
            + ACCOUNT_COLUMNS
            + " FROM paying_accounts WHERE tenant_id = ?"
            + " ORDER BY currency, set_at DESC, id DESC",
        ps -> ps.setObject(1, tenantId),
        BankFileRepository::account,
        "list paying accounts");
  }

  /**
   * Records a status report and what it said per supplier, together, once by the report's message
   * id.
   *
   * @return {@code false} when a report with that message id was already recorded
   */
  public boolean recordReport(
      UUID reportId,
      UUID tenantId,
      UUID runId,
      Pain002.Report report,
      UUID receivedBy,
      List<StatusRow> rows) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_status_reports (id,tenant_id,run_id,message_id,"
                      + " original_message_id,group_status,received_by) VALUES (?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, message_id) DO NOTHING")) {
            ps.setObject(1, reportId);
            ps.setObject(2, tenantId);
            ps.setObject(3, runId);
            ps.setString(4, report.messageId());
            ps.setString(5, report.originalMessageId());
            ps.setString(6, report.groupStatus());
            ps.setObject(7, receivedBy);
            if (ps.executeUpdate() == 0) return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_statuses (tenant_id,report_id,run_id,supplier_id,"
                      + " end_to_end_id,status,reason_code,payee_match,matched_name,held)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (StatusRow row : rows) {
              Pain002.TransactionStatus st = row.status();
              ps.setObject(1, tenantId);
              ps.setObject(2, reportId);
              ps.setObject(3, runId);
              ps.setObject(4, row.supplierId());
              ps.setString(5, st.endToEndId());
              ps.setString(6, st.status());
              ps.setString(7, cut(st.reasonCode(), 35));
              ps.setString(8, st.payeeMatch());
              ps.setString(9, st.matchedName());
              ps.setBoolean(10, st.held());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "record payment status report");
  }

  /** The latest bank check on each supplier's payment, for these runs, by run then supplier. */
  public Map<UUID, Map<UUID, PayeeCheck>> findChecks(UUID tenantId, Collection<UUID> runIds) {
    if (runIds.isEmpty()) return Map.of();
    var rows =
        query(
            "SELECT DISTINCT ON (s.run_id, s.supplier_id) s.run_id, s.supplier_id,"
                + "       s.end_to_end_id, s.status, s.reason_code, s.payee_match, s.matched_name,"
                + "       s.held, r.id AS report_id, r.message_id, h.released_by, h.released_at,"
                + "       h.reason AS release_reason"
                + "  FROM payment_statuses s"
                + "  JOIN payment_status_reports r ON r.tenant_id = s.tenant_id AND r.id = s.report_id"
                + "  LEFT JOIN payment_hold_releases h ON h.tenant_id = s.tenant_id"
                + "       AND h.report_id = s.report_id AND h.supplier_id = s.supplier_id"
                + " WHERE s.tenant_id = ? AND s.run_id = ANY(?)"
                + " ORDER BY s.run_id, s.supplier_id, r.received_at DESC, r.id DESC",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", runIds.toArray()));
            },
            rs -> Map.entry(rs.getObject("run_id", UUID.class), check(rs)),
            "find payee checks");
    Map<UUID, Map<UUID, PayeeCheck>> out = new HashMap<>();
    for (var e : rows) {
      out.computeIfAbsent(e.getKey(), k -> new HashMap<>())
          .put(e.getValue().supplierId(), e.getValue());
    }
    return out;
  }

  /**
   * Releases a held payment.
   *
   * @return {@code false} when it was already released
   */
  public boolean release(
      UUID tenantId, UUID reportId, UUID runId, UUID supplierId, UUID releasedBy, String reason) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_hold_releases (tenant_id,report_id,run_id,supplier_id,"
                      + " released_by,reason) VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, report_id, supplier_id) DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, reportId);
            ps.setObject(3, runId);
            ps.setObject(4, supplierId);
            ps.setObject(5, releasedBy);
            ps.setString(6, reason);
            return ps.executeUpdate() == 1;
          }
        },
        "release held payment");
  }

  private static PayingAccount account(ResultSet rs) throws SQLException {
    return new PayingAccount(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("currency"),
        rs.getString("account_name"),
        rs.getString("sort_code"),
        rs.getString("account_number"),
        rs.getString("iban"),
        rs.getString("bic"),
        rs.getString("service_user_number"),
        rs.getObject("set_by", UUID.class),
        rs.getObject("set_at", OffsetDateTime.class).toInstant());
  }

  private static PayeeCheck check(ResultSet rs) throws SQLException {
    OffsetDateTime released = rs.getObject("released_at", OffsetDateTime.class);
    return new PayeeCheck(
        rs.getObject("supplier_id", UUID.class),
        rs.getString("end_to_end_id"),
        rs.getString("status"),
        rs.getString("reason_code"),
        rs.getString("payee_match"),
        rs.getString("matched_name"),
        rs.getBoolean("held"),
        rs.getObject("report_id", UUID.class),
        rs.getString("message_id"),
        rs.getObject("released_by", UUID.class),
        released == null ? null : released.toInstant(),
        rs.getString("release_reason"));
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
