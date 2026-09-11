package com.shelfj.inventory.messaging;

import com.shelfj.inventory.service.FoodSafetyService;
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
 * Raises FoodSafetyCheckOverdue for every monitoring point whose check was missed, once per missed
 * due time. A check nobody was reminded to make is the gap a due-diligence defence falls into, and
 * a screen only helps whoever happens to look at it.
 */
@ApplicationScoped
public class FoodSafetyOverdueSweeper {

  private static final Logger LOG = System.getLogger(FoodSafetyOverdueSweeper.class.getName());

  @Inject FoodSafetyService service;

  @Inject
  @ConfigProperty(
      name = "shelfj.inventory.food-safety.overdue-sweeper.enabled",
      defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(
      name = "shelfj.inventory.food-safety.overdue-sweeper.interval-seconds",
      defaultValue = "900")
  long intervalSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.inventory.food-safety.overdue-sweeper.batch", defaultValue = "200")
  int batchSize;

  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager CDI startup */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Food-safety overdue sweeper disabled");
      return;
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "inventory-food-safety-overdue-sweeper");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sweepQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    LOG.log(Level.INFO, "Food-safety overdue sweeper started (every {0}s)", intervalSeconds);
  }

  private void sweepQuietly() {
    try {
      int alerted = service.sweepOverdue(batchSize);
      if (alerted > 0) {
        LOG.log(Level.INFO, "Food-safety overdue sweep raised {0} alert(s)", alerted);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Food-safety overdue sweep deferred: " + e.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
