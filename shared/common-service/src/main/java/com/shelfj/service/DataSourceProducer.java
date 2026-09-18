package com.shelfj.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.Optional;
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

  /**
   * Builds the CDI-managed {@link DataSource} bean from {@link ServiceSettings}. Called once by
   * CDI; the resulting pool is shared for the application's lifetime.
   *
   * @return a Hikari-backed pool sized from {@link ServiceSettings#dbPoolMaxSize()}, scoped to
   *     {@link ServiceSettings#dbSchema()}
   */
  /**
   * The pool as it was built, kept beside the bean CDI hands out.
   *
   * <p>Injecting {@code DataSource} gives a client proxy, and a proxy is neither an instance of
   * {@link HikariDataSource} nor willing to hand the real one back through {@code unwrap} — both
   * were tried, and both fail: the cast throws and the unwrap returns the proxy again. So the one
   * place that has the concrete pool, which is here, keeps a reference to it. {@link
   * DeepHealthResource} reads its figures through {@link #poolStats()}.
   */
  private volatile HikariDataSource pool;

  /**
   * The connection pool's own figures, for the deep health check.
   *
   * <p>Worth having: under load the two failures that look identical from a probe — a database that
   * is down and a pool with nothing left to give — want opposite remedies, and only the pool can
   * tell them apart.
   *
   * @return empty before the pool has been built, or after it has been closed
   */
  @SuppressWarnings("PMD.CloseResource") // reading the application's own pool, never owning it
  public Optional<PoolStats> poolStats() {
    HikariDataSource current = pool;
    if (current == null || current.isClosed()) return Optional.empty();
    HikariPoolMXBean bean = current.getHikariPoolMXBean();
    if (bean == null) return Optional.empty();
    return Optional.of(
        new PoolStats(
            current.getPoolName(),
            bean.getActiveConnections(),
            bean.getIdleConnections(),
            bean.getTotalConnections(),
            current.getMaximumPoolSize(),
            bean.getThreadsAwaitingConnection()));
  }

  /**
   * @param waiting threads queued for a connection; anything above zero under load is the pool, not
   *     the database
   */
  public record PoolStats(String name, int active, int idle, int total, int max, int waiting) {}

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
    HikariDataSource built = new HikariDataSource(config);
    pool = built;
    return built;
  }

  /**
   * Closes the pool on application shutdown, releasing all pooled connections.
   *
   * @param ds the {@link DataSource} bean produced by {@link #dataSource()}
   */
  @SuppressWarnings("PMD.CloseResource") // this IS the close — CDI calls it on shutdown
  void close(@Disposes DataSource ds) {
    if (ds instanceof HikariDataSource hikari) {
      hikari.close();
    }
  }
}
