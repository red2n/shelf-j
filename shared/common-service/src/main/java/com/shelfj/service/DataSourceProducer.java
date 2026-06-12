package com.shelfj.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;

/**
 * Produces the application {@link DataSource} from {@link ServiceSettings}, scoped to the service's
 * own schema (database-per-service). Shared so each service no longer hand-writes this.
 *
 * <p>Backed by HikariCP: the repository helpers open/close a connection per call, so an unpooled
 * DataSource would pay a full TCP + auth handshake to Postgres on every query and exhaust {@code
 * max_connections} under concurrency.
 */
@ApplicationScoped
public class DataSourceProducer {

  @Inject ServiceSettings settings;

  @Produces
  @ApplicationScoped
  public DataSource dataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.dbUrl());
    config.setUsername(settings.dbUser());
    config.setPassword(settings.dbPassword());
    config.setSchema(settings.dbSchema());
    config.setPoolName(settings.serviceName() + "-db");
    config.setMaximumPoolSize(settings.dbPoolMaxSize());
    config.setMinimumIdle(Math.min(2, settings.dbPoolMaxSize()));
    config.setConnectionTimeout(5_000);
    config.setMaxLifetime(1_800_000);
    return new HikariDataSource(config);
  }

  @SuppressWarnings("PMD.CloseResource") // this IS the close — CDI calls it on shutdown
  void close(@Disposes DataSource ds) {
    if (ds instanceof HikariDataSource hikari) {
      hikari.close();
    }
  }
}
