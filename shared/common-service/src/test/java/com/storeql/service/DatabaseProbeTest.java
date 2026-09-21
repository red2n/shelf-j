package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The readiness verdict, and the case the whole class exists for: a pool with nothing left to give
 * while the database itself is perfectly well. Reporting that as DOWN takes the replica out of the
 * load balancer at the peak that caused it, moves its traffic to its neighbours, and empties their
 * pools in turn.
 */
class DatabaseProbeTest {

  /** A clock the test moves by hand, so a grace window can be aged without waiting for it. */
  private static final class Ticking extends Clock {
    private Instant now = Instant.parse("2026-09-17T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }
  }

  private final Ticking clock = new Ticking();
  private final AtomicInteger acquisitions = new AtomicInteger();
  private volatile String refuseWith;
  private volatile boolean connectionValidates = true;

  /** A pool that hands out a connection, or refuses the way an exhausted one does. */
  private DataSource pool() {
    return (DataSource)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if (!"getConnection".equals(method.getName())) return null;
              acquisitions.incrementAndGet();
              String refusal = refuseWith;
              if (refusal != null) throw new SQLException(refusal);
              return connection();
            });
  }

  private Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("isValid".equals(method.getName())) return connectionValidates;
              Class<?> returns = method.getReturnType();
              if (returns == boolean.class) return false;
              if (returns == int.class) return 0;
              return null;
            });
  }

  private DatabaseProbe probe() {
    return DatabaseProbe.forTest(pool(), clock);
  }

  /** The pool's own wording when it has nothing left and the caller has waited its full timeout. */
  private static final String EXHAUSTED =
      "Connection is not available, request timed out after 5000ms";

  @Test
  @DisplayName("A database that answers is ready, and says when it last did")
  void aDatabaseThatAnswersIsReady() {
    DatabaseProbe probe = probe();
    probe.ping();

    DatabaseProbe.Verdict verdict = probe.verdict();
    assertTrue(verdict.up());
    assertTrue(verdict.detail().contains("answered"), verdict.detail());
    assertEquals(clock.instant(), verdict.lastGood());
    assertTrue(verdict.roundTripMillis() >= 0, "the attempt was timed");
  }

  @Test
  @DisplayName("Nothing is ready before the first attempt has landed")
  void nothingIsReadyBeforeTheFirstAttempt() {
    // The background thread starts the moment the bean does, so this window is milliseconds wide —
    // but claiming UP inside it would let traffic at a service that has never reached its database.
    DatabaseProbe.Verdict verdict = probe().verdict();

    assertFalse(verdict.up());
    assertTrue(verdict.detail().contains("has not been asked yet"), verdict.detail());
    assertEquals(0, acquisitions.get(), "and asking for the verdict did not go and find out");
  }

  @Test
  @DisplayName("A pool with nothing left to give is not a broken database")
  void aBusyPoolIsNotABrokenDatabase() {
    DatabaseProbe probe = probe();
    probe.ping();

    refuseWith = EXHAUSTED;
    clock.advance(Duration.ofSeconds(10));
    probe.ping();

    DatabaseProbe.Verdict verdict = probe.verdict();
    assertTrue(verdict.up(), "the replica keeps serving: the database answered ten seconds ago");
    assertTrue(verdict.detail().contains("busy pool"), verdict.detail());
    assertTrue(verdict.detail().contains(EXHAUSTED), "and it says what actually happened");
  }

  @Test
  @DisplayName("A database that stays gone is reported down once the grace window is out")
  void aDatabaseThatStaysGoneIsReportedDown() {
    DatabaseProbe probe = probe();
    probe.ping();

    refuseWith = "Connection refused";
    clock.advance(DatabaseProbe.GRACE.plusSeconds(1));
    probe.ping();

    DatabaseProbe.Verdict verdict = probe.verdict();
    assertFalse(verdict.up(), "31 seconds of nothing is an outage, not a burst");
    assertTrue(verdict.detail().contains("nothing has got through"), verdict.detail());
    assertTrue(verdict.detail().contains("Connection refused"), verdict.detail());
  }

  @Test
  @DisplayName("A database that comes back is ready again on the next attempt")
  void aDatabaseThatComesBackIsReadyAgain() {
    DatabaseProbe probe = probe();
    probe.ping();
    refuseWith = "Connection refused";
    clock.advance(DatabaseProbe.GRACE.plusSeconds(1));
    probe.ping();
    assertFalse(probe.verdict().up());

    refuseWith = null;
    clock.advance(Duration.ofSeconds(5));
    probe.ping();

    DatabaseProbe.Verdict verdict = probe.verdict();
    assertTrue(verdict.up());
    assertTrue(verdict.detail().contains("answered"), verdict.detail());
    assertEquals(clock.instant(), verdict.lastGood(), "the failure is behind it, not remembered");
  }

  @Test
  @DisplayName("Reading the verdict never goes near the pool, however often it is read")
  void theVerdictNeverTouchesThePool() {
    // This is the property the whole design rests on. Kubernetes reads readiness on every replica
    // every few seconds; if reading it cost a connection, the probe would be competing with the
    // traffic it is measuring, and under load it would lose.
    DatabaseProbe probe = probe();
    probe.ping();
    assertEquals(1, acquisitions.get());

    for (int i = 0; i < 500; i++) {
      probe.verdict();
    }

    assertEquals(1, acquisitions.get(), "500 probes, one connection — the one the prober took");
  }

  @Test
  @DisplayName("A connection that will not validate counts as a failure, not a pass")
  void aConnectionThatDoesNotValidateIsAFailure() {
    connectionValidates = false;
    DatabaseProbe probe = probe();
    probe.ping();

    DatabaseProbe.Verdict verdict = probe.verdict();
    assertFalse(verdict.up(), "a connection handed over but dead is not an answer");
    assertTrue(verdict.detail().contains("did not validate"), verdict.detail());
  }

  @Test
  @DisplayName("Liveness carries no dependency, and the build fails the day one is added")
  void livenessCarriesNoDependency() throws Exception {
    // The rule a pod's life depends on. A liveness check that reaches the database turns a slow
    // database into a restart storm: every replica is killed, every replacement hits the same slow
    // database, and the cluster restarts its way through an outage it cannot fix. That rule is one
    // injected field away from being broken at any time, so the build holds it rather than a
    // comment.
    for (Field field : HealthChecks.ProcessLiveness.class.getDeclaredFields()) {
      if (field.isSynthetic()) continue;
      assertEquals(
          ServiceSettings.class,
          field.getType(),
          "liveness may know the service's own name and nothing else — "
              + field.getName()
              + " is a dependency, and a failing liveness probe kills the pod");
    }
    assertTrue(HealthChecks.ProcessLiveness.class.isAnnotationPresent(Liveness.class));
    assertFalse(
        HealthChecks.ProcessLiveness.class.isAnnotationPresent(Readiness.class),
        "and it is not also a readiness check, which would put it back on /health/ready");

    // The database is asked on readiness instead, where failing only takes the replica out of
    // rotation — and asked through the probe, so the check itself never waits on the pool.
    assertTrue(HealthChecks.DatabaseReadiness.class.isAnnotationPresent(Readiness.class));
    assertFalse(HealthChecks.DatabaseReadiness.class.isAnnotationPresent(Liveness.class));
    assertEquals(
        DatabaseProbe.class,
        HealthChecks.DatabaseReadiness.class.getDeclaredField("probe").getType());
  }
}
