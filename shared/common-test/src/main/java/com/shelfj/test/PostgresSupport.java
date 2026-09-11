package com.shelfj.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Reusable Postgres Testcontainer support for service integration tests (docs/ARCHITECTURE.md §16).
 *
 * <p>Spins up a real Postgres in a container, optionally runs the service's Flyway migrations
 * against it, and hands back a {@link DataSource}. Keeps integration tests honest (real DB, real
 * SQL) without a shared instance.
 *
 * <pre>{@code
 * var pg = PostgresSupport.start();
 * pg.migrate("classpath:db/migration");
 * DataSource ds = pg.dataSource();
 * ... run repo tests ...
 * pg.stop();
 * }</pre>
 */
public final class PostgresSupport implements AutoCloseable {

  /**
   * Counts non-v7 ids table by table in one statement: query_to_xml runs the per-table count inside
   * Postgres, so no table name is ever concatenated into SQL here. Character 15 of a uuid's text is
   * its version digit.
   */
  private static final String NON_V7_IDS =
      "SELECT table_name, rows FROM ("
          + " SELECT c.table_schema || '.' || c.table_name AS table_name,"
          + "  (xpath('/row/n/text()', query_to_xml(format("
          + "   'SELECT count(*) AS n FROM %I.%I WHERE substring(id::text, 15, 1) <> ''7''',"
          + "   c.table_schema, c.table_name), false, true, '')))[1]::text::bigint AS rows"
          + " FROM information_schema.columns c"
          + " JOIN information_schema.tables t"
          + "  ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
          + " WHERE c.column_name = 'id' AND c.data_type = 'uuid' AND t.table_type = 'BASE TABLE'"
          + "  AND c.table_schema NOT IN ('pg_catalog', 'information_schema')"
          + ") counted WHERE rows > 0 ORDER BY table_name";

  @SuppressWarnings("PMD.NoDatabaseMintedIds") // names the generators to find them, never calls one
  private static final String ID_GENERATING_DEFAULTS =
      "SELECT table_schema || '.' || table_name || '.' || column_name || ' DEFAULT ' || column_default"
          + " FROM information_schema.columns"
          + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"
          + " AND (column_default ILIKE '%gen_random_uuid%' OR column_default ILIKE '%uuid_generate_v%'"
          + "  OR column_default ILIKE '%uuid_v7%')"
          + " ORDER BY 1";

  private final PostgreSQLContainer<?> container;

  private PostgresSupport(PostgreSQLContainer<?> container) {
    this.container = container;
  }

  /**
   * Start a Postgres 16 container.
   *
   * @return a started {@code PostgresSupport} with database {@code shelfj_test}, user/password
   *     {@code shelfj}/{@code shelfj}; call {@link #close()} (or {@link #stop()}) when done
   * @throws org.testcontainers.containers.ContainerLaunchException if the container fails to start
   */
  public static PostgresSupport start() {
    @SuppressWarnings("resource")
    PostgreSQLContainer<?> c =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shelfj_test")
            .withUsername("shelfj")
            .withPassword("shelfj");
    c.start();
    return new PostgresSupport(c);
  }

  /**
   * Run Flyway migrations from the given location (e.g. {@code "classpath:db/migration"}).
   *
   * @param location the Flyway migration location to apply
   * @return this, for chaining after {@link #start()}
   * @throws org.flywaydb.core.api.FlywayException if a migration fails to apply
   */
  public PostgresSupport migrate(String location) {
    Flyway.configure()
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .locations(location)
        .load()
        .migrate();
    return this;
  }

  /**
   * @return a fresh unpooled {@link DataSource} pointing at this container
   */
  public DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(container.getJdbcUrl());
    ds.setUser(container.getUsername());
    ds.setPassword(container.getPassword());
    return ds;
  }

  /**
   * @return the JDBC URL of the running container
   */
  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  /**
   * @return the database user ({@code shelfj})
   */
  public String username() {
    return container.getUsername();
  }

  /**
   * @return the database password ({@code shelfj})
   */
  public String password() {
    return container.getPassword();
  }

  /**
   * Checks that every row the test left behind has a version-7 id, then stops and removes the
   * container — even when the check fails.
   *
   * <p>Shelf-J mints only UUIDv7 ids. Checking here, at the end of every integration test class,
   * turns any path that still stores another version — a column default, SQL that makes its own
   * uuid, a fixture — into a failure that names the table.
   *
   * @throws AssertionError if any table's {@code id} column holds an id that is not version 7
   */
  public void stop() {
    try {
      List<String> defaults = idGeneratingDefaults();
      if (!defaults.isEmpty()) {
        throw new AssertionError(
            "columns that generate their own uuids: "
                + defaults
                + ". Drop the DEFAULT and bind Ids.newId(); see docs/coding-standards.md §3.");
      }
      Map<String, Long> offenders = nonV7Ids();
      if (!offenders.isEmpty()) {
        throw new AssertionError(
            "ids that are not UUIDv7 (table=rows): "
                + offenders
                + ". Mint ids with Ids.newId(); see docs/coding-standards.md §3.");
      }
    } finally {
      container.stop();
    }
  }

  /**
   * @return every column, in any schema, whose default calls a uuid generator — the same check as
   *     common-service's afterMigrate.sql, made here because a service that fails to migrate only
   *     logs a warning and keeps running
   */
  public List<String> idGeneratingDefaults() {
    List<String> offenders = new ArrayList<>();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(ID_GENERATING_DEFAULTS);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        offenders.add(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not audit column defaults", e);
    }
    return offenders;
  }

  /**
   * @return every base table with a uuid {@code id} column that holds ids of another version, with
   *     how many; empty when all ids are v7
   */
  public Map<String, Long> nonV7Ids() {
    Map<String, Long> offenders = new TreeMap<>();
    try (Connection c = dataSource().getConnection();
        PreparedStatement ps = c.prepareStatement(NON_V7_IDS);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        offenders.put(rs.getString("table_name"), rs.getLong("rows"));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not audit id versions", e);
    }
    return offenders;
  }

  @Override
  public void close() {
    stop();
  }
}
