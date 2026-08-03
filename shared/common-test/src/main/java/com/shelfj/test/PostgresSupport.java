package com.shelfj.test;

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

  /** Stops and removes the container. */
  public void stop() {
    container.stop();
  }

  @Override
  public void close() {
    stop();
  }
}
