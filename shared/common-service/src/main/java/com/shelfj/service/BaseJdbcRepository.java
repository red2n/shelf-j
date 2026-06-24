package com.shelfj.service;

import com.shelfj.web.ApiException;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC utility base for all service repositories. Provides the DataSource, transaction management,
 * one-shot helpers, and shared functional interfaces — once, so repos don't each carry them.
 *
 * <p>CDI injects the superclass {@code dataSource} field when the concrete repo bean is resolved.
 */
public abstract class BaseJdbcRepository {

  /** PostgreSQL SQLSTATE code for unique-constraint violations (23505). */
  protected static final String UNIQUE_VIOLATION = "23505";

  @Inject protected DataSource dataSource;

  // ── Functional interfaces ─────────────────────────────────────────────────

  @FunctionalInterface
  protected interface TxWork<R> {
    R run(Connection c) throws SQLException;
  }

  @FunctionalInterface
  protected interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  @FunctionalInterface
  protected interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  // ── Transaction ───────────────────────────────────────────────────────────

  /**
   * Run {@code work} inside a single JDBC transaction. Re-throws {@link ApiException} after
   * rollback (so domain-level 4xx/5xx exceptions propagate cleanly). Routes SQL exceptions through
   * {@link #handleTxSqlException} so subclasses can map unique-violation codes to service-specific
   * error responses.
   */
  protected <R> R inTx(TxWork<R> work, String what) {
    try (Connection c = acquireConnection()) {
      c.setAutoCommit(false);
      try {
        R r = work.run(c);
        c.commit();
        return r;
      } catch (ApiException ae) {
        c.rollback();
        throw ae;
      } catch (SQLException e) {
        c.rollback();
        throw handleTxSqlException(what, e);
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw dbError(what + " (connection)", e);
    }
  }

  /**
   * Acquires a pooled connection, retrying briefly on transient failures (observed in practice as
   * pgbouncer transaction-pooling contention under concurrent writes — see the {@code
   * pgbouncer-gotchas} note: same call retried 1-2x always succeeded). This only retries the
   * acquire step itself, never {@code work.run(c)}, so a retry can never double-execute business
   * logic.
   */
  private Connection acquireConnection() throws SQLException {
    final int maxAttempts = 3;
    SQLException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return dataSource.getConnection();
      } catch (SQLException e) {
        last = e;
        if (attempt == maxAttempts) break;
        try {
          Thread.sleep(100L * attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw last;
  }

  /**
   * Override to map a transaction-level {@link SQLException} to a service-specific exception (e.g.
   * check {@link #UNIQUE_VIOLATION} and return a domain 409). Default: generic DB_ERROR 500.
   */
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ── One-shot helpers ──────────────────────────────────────────────────────

  /** Execute a single DML statement. Converts unique-constraint violations to a generic 409. */
  protected void exec(String sql, Binder binder, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      ps.executeUpdate();
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState()))
        throw new ApiException(409, "DUPLICATE", "Already exists", List.of(), e);
      throw dbError(what, e);
    }
  }

  /** Execute a SELECT and map each row; returns an empty list when nothing matches. */
  protected <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper, String what) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) out.add(mapper.map(rs));
        return out;
      }
    } catch (SQLException e) {
      throw dbError(what, e);
    }
  }

  // ── Idempotency ───────────────────────────────────────────────────────────

  /**
   * Insert an event-id + consumer pair into {@code processed_events}. Returns {@code true} if the
   * row was inserted (first time seen); {@code false} if it was already present (duplicate). Common
   * to every service that consumes Kafka events — defined once here so it doesn't need to be copied
   * into each repo.
   */
  public boolean markProcessedIfNew(UUID eventId, String consumer) {
    try (Connection c = dataSource.getConnection()) {
      return markProcessedIfNewTx(c, eventId, consumer);
    } catch (SQLException e) {
      throw dbError("mark processed event", e);
    }
  }

  /**
   * Transaction-scoped variant of {@link #markProcessedIfNew}: runs on the caller's connection so
   * the dedupe mark commits (or rolls back) atomically WITH the business write. Marking in a
   * separate transaction first would permanently swallow the event if the write then failed.
   */
  protected static boolean markProcessedIfNewTx(Connection c, UUID eventId, String consumer)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO processed_events (event_id, consumer) VALUES (?,?)"
                + " ON CONFLICT (event_id) DO NOTHING")) {
      ps.setObject(1, eventId);
      ps.setString(2, consumer);
      return ps.executeUpdate() > 0;
    }
  }

  // ── Error ─────────────────────────────────────────────────────────────────

  protected static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
