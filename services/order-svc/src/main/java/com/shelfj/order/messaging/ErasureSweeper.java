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
 * Redacts orders that finished after their customer was erased (SJ-D43). An erasure holds an open
 * order's delivery details only while they are needed to complete it; this is what lets them go
 * once they are not. Hourly by default — the statutory deadline is a month.
 */
@ApplicationScoped
public class ErasureSweeper {

  private static final Logger LOG = System.getLogger(ErasureSweeper.class.getName());

  @Inject OrderService service;

  @Inject
  @ConfigProperty(name = "shelfj.order.erasure-sweeper.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "shelfj.order.erasure-sweeper.interval-seconds", defaultValue = "3600")
  long intervalSeconds;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Erasure sweeper disabled");
      return;
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "order-erasure-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Erasure sweeper started (every {0}s)", intervalSeconds);
  }

  private void sweepQuietly() {
    try {
      int redacted = service.sweepErasures();
      if (redacted > 0) {
        LOG.log(Level.INFO, "Erasure sweeper redacted {0} finished order(s)", redacted);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Erasure sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
