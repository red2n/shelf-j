package com.shelfj.service;

import com.shelfj.web.ApiException;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
    try (Connection c = dataSource.getConnection()) {
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

  // ── Error ─────────────────────────────────────────────────────────────────

  protected static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
