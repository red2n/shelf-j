package com.shelfj.order.messaging;

import com.shelfj.order.service.OrderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Background sweeper that cancels PENDING orders older than a TTL — the stranded pay-later orders
 * whose client died after {@code POST /orders} and never paid (a paid order would have confirmed).
 * Without this they sit PENDING forever; inventory-svc's reservation sweeper reclaims their stock
 * hold, but the order itself is never resolved. Each cancellation emits OrderCancelled (inventory
 * releases any remaining hold; payment-svc no-ops since nothing was captured). Mirrors inventory's
 * {@code ReservationSweeper}.
 */
@ApplicationScoped
public class PendingOrderSweeper {

  private static final Logger LOG = System.getLogger(PendingOrderSweeper.class.getName());
  private static final int BATCH_LIMIT = 200;

  @Inject OrderService service;

  @Inject
  @ConfigProperty(name = "shelfj.order.pending-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "shelfj.order.pending-sweeper.interval-seconds", defaultValue = "300")
  long intervalSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.order.pending-sweeper.ttl-hours", defaultValue = "24")
  int ttlHours;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Pending-order sweeper disabled");
      return;
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "order-pending-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(
        Level.INFO,
        "Pending-order sweeper started (every {0}s, TTL {1}h)",
        intervalSeconds,
        ttlHours);
  }

  private void sweepQuietly() {
    try {
      int cancelled = service.sweepExpiredPendingOrders(ttlHours, BATCH_LIMIT);
      if (cancelled > 0) {
        LOG.log(Level.INFO, "Pending-order sweeper cancelled {0} expired order(s)", cancelled);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Pending-order sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
