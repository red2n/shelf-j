package com.storeql.payment.repo;

import com.storeql.payment.domain.Terminals;
import com.storeql.payment.domain.Terminals.Attempt;
import com.storeql.payment.domain.Terminals.Terminal;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The terminals a business has, and every attempt made at one (07.16).
 *
 * <p><b>The claim happens before the card does.</b> {@link #claim} writes the attempt and its
 * idempotency key in one transaction, <em>before</em> the terminal is asked for anything — so a
 * retried "take card" finds the first attempt and returns it rather than starting a second EMV
 * transaction. A terminal payment is the canonical double-charge: the cashier presses the button
 * again because the screen did not change, and a real customer is charged twice. Claiming
 * afterwards would leave the window open for exactly as long as a cardholder takes to enter a PIN.
 */
@ApplicationScoped
public class TerminalRepository extends BaseJdbcRepository {

  // ── the devices ─────────────────────────────────────────────────────────────

  private static final String TERMINAL_COLUMNS =
      "SELECT id, tenant_id, store_id, label, vendor, serial, status, retired_reason, created_at,"
          + " updated_at FROM card_terminals";

  private static final String INSERT_TERMINAL =
      "INSERT INTO card_terminals (id, tenant_id, store_id, label, vendor, serial, status,"
          + " created_at, created_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";

  private static final String RETIRE_TERMINAL =
      "UPDATE card_terminals SET status = 'RETIRED', retired_reason = ?, updated_at = ?"
          + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'";

  public Terminal add(Terminal t, UUID actorId) {
    exec(
        INSERT_TERMINAL,
        ps -> {
          ps.setObject(1, t.id());
          ps.setObject(2, t.tenantId());
          ps.setObject(3, t.storeId());
          ps.setString(4, t.label());
          ps.setString(5, t.vendor());
          ps.setString(6, t.serial());
          ps.setString(7, Terminals.ACTIVE);
          ps.setObject(8, t.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(9, actorId);
          ps.setObject(10, t.createdAt().atOffset(ZoneOffset.UTC));
        },
        "register a card terminal");
    return t;
  }

  /** Every terminal a business has, newest first, retired ones included so history reads. */
  public List<Terminal> of(UUID tenantId) {
    return query(
        TERMINAL_COLUMNS + " WHERE tenant_id = ? ORDER BY created_at DESC",
        ps -> ps.setObject(1, tenantId),
        TerminalRepository::readTerminal,
        "card terminals");
  }

  public Optional<Terminal> find(UUID tenantId, UUID id) {
    return query(
            TERMINAL_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            TerminalRepository::readTerminal,
            "a card terminal")
        .stream()
        .findFirst();
  }

  /**
   * @return true when this call retired it; false when it was already retired or is not theirs
   */
  public boolean retire(UUID tenantId, UUID id, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(RETIRE_TERMINAL)) {
            ps.setString(1, reason);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() == 1;
          }
        },
        "retire a card terminal");
  }

  // ── the attempts ────────────────────────────────────────────────────────────

  private static final String ATTEMPT_COLUMNS =
      "SELECT id, tenant_id, store_id, terminal_id, order_id, amount, currency, kind, refund_of,"
          + " state, outcome_detail, scheme, pan_last4, auth_code, aid, application_label,"
          + " entry_mode, verification, provider_ref, payment_id, requested_at, requested_by,"
          + " settled_at FROM terminal_payments";

  private static final String INSERT_ATTEMPT =
      "INSERT INTO terminal_payments (id, tenant_id, store_id, terminal_id, order_id, amount,"
          + " currency, kind, refund_of, state, requested_at, requested_by)"
          + " VALUES (?,?,?,?,?,?,?,?,?,'REQUESTED',?,?)";

  private static final String CLAIM_KEY =
      "INSERT INTO terminal_payment_keys (tenant_id, idempotency_key, terminal_payment_id,"
          + " created_at) VALUES (?,?,?,?)";

  private static final String BY_KEY =
      ATTEMPT_COLUMNS
          + " WHERE tenant_id = ? AND id = (SELECT terminal_payment_id FROM terminal_payment_keys"
          + " WHERE tenant_id = ? AND idempotency_key = ?)";

  /**
   * Settles an attempt: one move out of {@code REQUESTED}, and never back.
   *
   * <p>{@code AND state = 'REQUESTED'} is what makes it one move. Two answers for one attempt — a
   * cancel racing the cardholder's tap — leave the first, and the caller learns from the row count
   * that it did not win rather than overwriting an approval with a cancellation.
   */
  private static final String SETTLE =
      "UPDATE terminal_payments SET state = ?, outcome_detail = ?, scheme = ?, pan_last4 = ?,"
          + " auth_code = ?, aid = ?, application_label = ?, entry_mode = ?, verification = ?,"
          + " provider_ref = ?, settled_at = ? WHERE tenant_id = ? AND id = ? AND state ="
          + " 'REQUESTED'";

  private static final String ATTACH_PAYMENT =
      "UPDATE terminal_payments SET payment_id = ? WHERE tenant_id = ? AND id = ?"
          + " AND payment_id IS NULL";

  /**
   * Writes the attempt and claims the key, in one transaction, before the terminal is touched.
   *
   * @return the attempt as claimed, or the one an earlier call with this key already claimed
   */
  public Attempt claim(Attempt attempt, String idempotencyKey) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            Attempt existing = byKeyTx(c, attempt.tenantId(), idempotencyKey);
            if (existing != null) return existing;
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_ATTEMPT)) {
            ps.setObject(1, attempt.id());
            ps.setObject(2, attempt.tenantId());
            ps.setObject(3, attempt.storeId());
            ps.setObject(4, attempt.terminalId());
            ps.setObject(5, attempt.orderId());
            ps.setBigDecimal(6, attempt.amount());
            ps.setString(7, attempt.currency());
            ps.setString(8, attempt.kind());
            ps.setObject(9, attempt.refundOf());
            ps.setObject(10, attempt.requestedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(11, attempt.requestedBy());
            ps.executeUpdate();
          }
          if (idempotencyKey != null) {
            try (PreparedStatement ps = c.prepareStatement(CLAIM_KEY)) {
              ps.setObject(1, attempt.tenantId());
              ps.setString(2, idempotencyKey);
              ps.setObject(3, attempt.id());
              ps.setObject(4, attempt.requestedAt().atOffset(ZoneOffset.UTC));
              ps.executeUpdate();
            }
          }
          return attempt;
        },
        "claim a terminal payment");
  }

  private Attempt byKeyTx(java.sql.Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(BY_KEY)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, tenantId);
      ps.setString(3, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readAttempt(rs) : null;
      }
    }
  }

  /**
   * @return true when this call settled it; false when something already had
   */
  public boolean settle(UUID tenantId, UUID attemptId, Terminals.Outcome outcome) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(SETTLE)) {
            ps.setString(1, outcome.state());
            ps.setString(2, outcome.detail());
            ps.setString(3, outcome.scheme());
            ps.setString(4, outcome.panLast4());
            ps.setString(5, outcome.authCode());
            ps.setString(6, outcome.aid());
            ps.setString(7, outcome.applicationLabel());
            ps.setString(8, outcome.entryMode());
            ps.setString(9, outcome.verification());
            ps.setString(10, outcome.providerRef());
            ps.setObject(11, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(12, tenantId);
            ps.setObject(13, attemptId);
            return ps.executeUpdate() == 1;
          }
        },
        "settle a terminal payment");
  }

  public void attachPayment(UUID tenantId, UUID attemptId, UUID paymentId) {
    exec(
        ATTACH_PAYMENT,
        ps -> {
          ps.setObject(1, paymentId);
          ps.setObject(2, tenantId);
          ps.setObject(3, attemptId);
        },
        "attach a payment to a terminal attempt");
  }

  public Optional<Attempt> attempt(UUID tenantId, UUID id) {
    return query(
            ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            TerminalRepository::readAttempt,
            "a terminal attempt")
        .stream()
        .findFirst();
  }

  /**
   * Every attempt against one order, oldest first: a declined card then a cash tender reads in
   * order.
   */
  public List<Attempt> attemptsOf(UUID tenantId, UUID orderId) {
    return query(
        ATTEMPT_COLUMNS + " WHERE tenant_id = ? AND order_id = ? ORDER BY requested_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        TerminalRepository::readAttempt,
        "terminal attempts for an order");
  }

  /**
   * A collision on the idempotency key is a concurrent identical request, not a server fault.
   *
   * <p>{@link #claim} checks for the key inside its own transaction, which handles the ordinary
   * retry. A true race — two presses landing at once — reaches the primary key instead, and the
   * default mapping would turn it into a 500. It is a 409 with a name the caller can act on: ask
   * again and the first attempt comes back, which is exactly what the guard is for.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return com.storeql.web.ApiException.conflict(
          "TERMINAL_REQUEST_IN_FLIGHT",
          "An identical request is already going to the terminal; ask again for its outcome");
    }
    return super.handleTxSqlException(what, e);
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Terminal readTerminal(ResultSet rs) throws SQLException {
    return new Terminal(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("label"),
        rs.getString("vendor"),
        rs.getString("serial"),
        rs.getString("status"),
        rs.getString("retired_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Attempt readAttempt(ResultSet rs) throws SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("terminal_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("kind"),
        rs.getObject("refund_of", UUID.class),
        rs.getString("state"),
        rs.getString("outcome_detail"),
        rs.getString("scheme"),
        rs.getString("pan_last4"),
        rs.getString("auth_code"),
        rs.getString("aid"),
        rs.getString("application_label"),
        rs.getString("entry_mode"),
        rs.getString("verification"),
        rs.getString("provider_ref"),
        rs.getObject("payment_id", UUID.class),
        instant(rs, "requested_at"),
        rs.getObject("requested_by", UUID.class),
        instant(rs, "settled_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
