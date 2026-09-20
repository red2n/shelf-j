package com.storeql.service;

import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * {@code GET /admin/health}: the deep check — what the service's dependencies are actually doing,
 * asked now.
 *
 * <p>This is the route to open when a service is behaving oddly and the probes disagree with the
 * symptoms. It makes a real round trip to the database rather than reading a cached verdict, times
 * it, and prints the connection pool's own figures beside it, because the two questions an operator
 * has under load are different ones and only the pool can tell them apart:
 *
 * <ul>
 *   <li><b>Is the database down?</b> — the round trip fails, or takes seconds.
 *   <li><b>Is the database fine and the service simply out of connections?</b> — the round trip is
 *       quick, {@code active} equals {@code max}, and {@code waiting} is not zero. The answer then
 *       is a bigger pool or fewer callers, and restarting the pod would achieve nothing.
 * </ul>
 *
 * <p><b>No probe calls this.</b> It blocks for as long as the database makes it, which is exactly
 * what a kubelet probe must never do — see {@link HealthChecks} for which endpoint each probe
 * belongs on. It also says more about the inside of the service than an unauthenticated caller
 * should learn, so it sits under {@code /admin/}, where the authorisation filter gates it to
 * management.
 */
@Path("/admin/health")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class DeepHealthResource {

  /** A constant statement: nothing here is built from an identifier. */
  private static final String PING = "SELECT 1";

  /**
   * One dependency, as it answered just now.
   *
   * @param millis how long the round trip took, or null where there was nothing to time
   */
  public record Dependency(String name, boolean up, String detail, Long millis) {}

  /**
   * The connection pool's own figures.
   *
   * @param waiting threads queued for a connection; anything above zero under load is the pool, not
   *     the database
   */
  public record Pool(String name, int active, int idle, int total, int max, int waiting) {}

  /**
   * @param up whether every dependency answered
   * @param probeVerdict what the readiness probe is currently saying, so the two can be compared
   */
  public record DeepHealth(
      String service, boolean up, List<Dependency> dependencies, Pool pool, String probeVerdict) {
    public DeepHealth {
      dependencies = List.copyOf(dependencies);
    }
  }

  @Inject DataSource dataSource;
  @Inject DataSourceProducer dataSources;
  @Inject DatabaseProbe probe;
  @Inject ServiceSettings settings;
  @Inject TenantContext ctx;

  /**
   * The deep check.
   *
   * @return every dependency as it answered just now, the pool's figures, and what the readiness
   *     probe is currently saying, so a disagreement between the two is visible
   * @throws com.storeql.web.ApiException {@code 403} unless the caller is management
   */
  @GET
  public ApiResponse<DeepHealth> deep() {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    List<Dependency> dependencies = new ArrayList<>();
    dependencies.add(database());
    dependencies.add(kafkaConsumers());
    boolean up = dependencies.stream().allMatch(Dependency::up);
    return ApiResponse.ok(
        new DeepHealth(settings.serviceName(), up, dependencies, pool(), probe.verdict().detail()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /** A query the database has to actually answer — not a pool validation, which may not leave. */
  private Dependency database() {
    long started = System.nanoTime();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(PING);
        ResultSet rows = statement.executeQuery()) {
      boolean answered = rows.next();
      long took = millisSince(started);
      return new Dependency(
          "database",
          answered,
          answered ? "answered a query on the service's own schema" : "the query returned no row",
          took);
    } catch (Exception e) {
      return new Dependency(
          "database", false, String.valueOf(e.getMessage()), millisSince(started));
    }
  }

  private static Dependency kafkaConsumers() {
    Set<String> failed = KafkaConsumerRegistry.failedConsumers();
    return new Dependency(
        "kafka-consumers",
        failed.isEmpty(),
        failed.isEmpty() ? "all running" : "not running: " + String.join(", ", failed),
        null);
  }

  /**
   * The pool's figures, read from the one place that has the concrete pool.
   *
   * <p>Not from the injected {@code DataSource}: what CDI hands out is a client proxy, which is
   * neither an instance of {@code HikariDataSource} nor willing to return the real one through
   * JDBC's {@code unwrap} — the cast throws and the unwrap hands the proxy back. Both were tried
   * against the running stack and both failed, the second with a 500 that the live flow caught.
   * {@link DataSourceProducer} builds the pool, so it keeps the reference and answers for it.
   *
   * @return null before the pool exists or after it is closed, which the page shows as no pool
   *     rather than as a failure
   */
  private Pool pool() {
    return dataSources
        .poolStats()
        .map(p -> new Pool(p.name(), p.active(), p.idle(), p.total(), p.max(), p.waiting()))
        .orElse(null);
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }
}
