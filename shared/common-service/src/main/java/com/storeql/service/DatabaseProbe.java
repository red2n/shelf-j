package com.storeql.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Whether the database is answering — asked in the background, so nothing on a probe's path ever
 * waits for it.
 *
 * <p><b>Why this is not simply a query inside the readiness check.</b> Kubernetes calls readiness
 * every few seconds on every replica. A check that borrows a pooled connection competes with real
 * traffic for the pool it is measuring, and under load it loses: {@code getConnection()} queues for
 * the pool's whole connection timeout, the probe exceeds its own {@code timeoutSeconds}, and the
 * replica is taken out of the load balancer. The traffic it was serving moves to its neighbours,
 * whose pools then saturate too. The cluster removes every replica of a service that is busy but
 * working, which is the outage the probe existed to prevent.
 *
 * <p>So the question is asked on one background thread, on a fixed delay, and the health check
 * reads the answer it left behind. Probing costs one connection every {@link #PERIOD} no matter how
 * many probes arrive, and the check returns in microseconds whatever the database is doing.
 *
 * <p><b>A busy pool is not a broken database.</b> When an attempt fails but one succeeded within
 * {@link #GRACE}, the verdict stays UP and says why: the database demonstrably answered a moment
 * ago, so the service is saturated, not broken, and taking it out of rotation would remove capacity
 * at exactly the peak that caused it. Only when nothing has got through for the whole grace window
 * is the database reported as genuinely unreachable.
 *
 * <p>Liveness never consults this at all — see {@link HealthChecks}.
 */
@ApplicationScoped
public class DatabaseProbe {

  /** How often the database is asked, measured from the end of the previous attempt. */
  static final Duration PERIOD = Duration.ofSeconds(5);

  /**
   * How long a database that answered is trusted to still be there while attempts are failing. Six
   * attempts at {@link #PERIOD}: long enough to ride out a burst that empties the pool, short
   * enough that a database which has genuinely gone takes the replica out within half a minute.
   */
  static final Duration GRACE = Duration.ofSeconds(30);

  /** The per-attempt validation timeout, well inside a probe's own patience. */
  static final int VALIDATION_SECONDS = 2;

  /**
   * What the probe knows.
   *
   * @param up whether the database should be treated as reachable
   * @param detail why, in words an operator can act on
   * @param lastGood when the database last answered, or null when it never has
   * @param roundTripMillis how long the last attempt took, or -1 before the first one
   */
  public record Verdict(boolean up, String detail, Instant lastGood, long roundTripMillis) {}

  /**
   * One immutable snapshot, replaced whole by the single probing thread, so a reader can never see
   * a success and a failure mixed together.
   */
  private record State(Instant lastGood, String failure, long roundTripMillis) {}

  @Inject DataSource dataSource;

  private Clock clock = Clock.systemUTC();
  private volatile State state = new State(null, null, -1);
  private ScheduledExecutorService pinger;

  /** Starts the background thread. The first attempt runs at once, off this thread. */
  @PostConstruct
  void start() {
    pinger =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "db-probe");
              thread.setDaemon(true);
              return thread;
            });
    // Fixed *delay*, not rate: an attempt that takes the pool's whole timeout must not leave a
    // queue of attempts behind it waiting on the same exhausted pool.
    pinger.scheduleWithFixedDelay(this::ping, 0, PERIOD.toSeconds(), TimeUnit.SECONDS);
  }

  /** Stops the background thread on shutdown. */
  @PreDestroy
  void stop() {
    if (pinger != null) {
      pinger.shutdownNow();
    }
  }

  /** For tests: a data source to ask and a clock to age its answers with; nothing scheduled. */
  static DatabaseProbe forTest(DataSource dataSource, Clock clock) {
    DatabaseProbe probe = new DatabaseProbe();
    probe.dataSource = dataSource;
    probe.clock = clock;
    return probe;
  }

  /**
   * One attempt. Only the probing thread calls this, so reading {@link #state} and writing it back
   * needs no lock.
   */
  void ping() {
    long started = System.nanoTime();
    State previous = state;
    try (Connection connection = dataSource.getConnection()) {
      boolean valid = connection.isValid(VALIDATION_SECONDS);
      long took = millisSince(started);
      state =
          valid
              ? new State(clock.instant(), null, took)
              : new State(
                  previous.lastGood(),
                  "the connection did not validate within " + VALIDATION_SECONDS + "s",
                  took);
    } catch (Exception e) {
      // Anything at all: the driver's SQLException, the pool's timeout while it is exhausted, a
      // RuntimeException from a closed pool during shutdown. None of them is worth a stack trace
      // every five seconds; the message is what the verdict carries.
      state = new State(previous.lastGood(), String.valueOf(e.getMessage()), millisSince(started));
    }
  }

  /**
   * The current verdict. Never touches the database and never blocks, whatever the database is
   * doing — which is the whole point of the class.
   *
   * @return UP while the database answered within {@link #GRACE}, with the reason either way
   */
  public Verdict verdict() {
    State now = state;
    Instant good = now.lastGood();
    if (good == null) {
      return new Verdict(
          false,
          now.failure() == null ? "the database has not been asked yet" : now.failure(),
          null,
          now.roundTripMillis());
    }
    long secondsAgo = Duration.between(good, clock.instant()).toSeconds();
    if (now.failure() == null) {
      return new Verdict(true, "answered " + secondsAgo + "s ago", good, now.roundTripMillis());
    }
    if (secondsAgo < GRACE.toSeconds()) {
      return new Verdict(
          true,
          "the last attempt failed ("
              + now.failure()
              + ") but the database answered "
              + secondsAgo
              + "s ago, so this is a busy pool rather than an outage",
          good,
          now.roundTripMillis());
    }
    return new Verdict(
        false,
        "nothing has got through for " + secondsAgo + "s: " + now.failure(),
        good,
        now.roundTripMillis());
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }
}
